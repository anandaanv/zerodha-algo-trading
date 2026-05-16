package com.dtech.aitrader.service;

import com.dtech.aitrader.data.AiLevelsSuppressed;
import com.dtech.aitrader.repository.AiLevelsSuppressedRepository;
import com.dtech.kitecon.data.UserChartState;
import com.dtech.kitecon.repository.UserChartStateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiDrawingsMerger {
    private final AiTraderConfigService configService;
    private final UserChartStateRepository chartStateRepository;
    private final AiLevelsSuppressedRepository suppressedRepository;
    private final ObjectMapper objectMapper;

    public MergeResult mergeIntoChartState(String symbol, String tabId, Long layoutId, String timeframe, JsonNode aiLevelsJson) {
        log.info("Merging AI levels into chart state: symbol={}, tabId={}, layoutId={}, timeframe={}",
                symbol, tabId, layoutId, timeframe);

        try {
            String scopedSymbol = tabId + ":" + symbol;
            int mergedCount = 0;
            int suppressedCount = 0;
            List<String> suppressedIds = new ArrayList<>();

            // Load or create UserChartState
            UserChartState chartState = chartStateRepository
                    .findBySymbolAndLayoutIdAndTimeframe(scopedSymbol, layoutId, timeframe)
                    .orElse(chartStateRepository
                            .findBySymbolAndLayoutIdAndTimeframeIsNull(scopedSymbol, layoutId)
                            .orElse(UserChartState.builder()
                                    .symbol(scopedSymbol)
                                    .layoutId(layoutId)
                                    .timeframe(timeframe)
                                    .overlaysJson("{\"sources\":{},\"groups\":{}}")
                                    .build()));

            // Parse overlays JSON
            ObjectNode overlaysNode = (ObjectNode) objectMapper.readTree(
                    chartState.getOverlaysJson() != null ? chartState.getOverlaysJson() : "{\"sources\":{},\"groups\":{}}"
            );
            if (!overlaysNode.has("sources")) {
                overlaysNode.set("sources", objectMapper.createObjectNode());
            }
            ObjectNode sources = (ObjectNode) overlaysNode.get("sources");

            // Purge any existing ai_-prefixed keys so each run replaces (not appends).
            // Also clears garbage from older broken-format runs.
            List<String> aiKeysToRemove = new ArrayList<>();
            sources.fieldNames().forEachRemaining(k -> { if (k.startsWith("ai_")) aiKeysToRemove.add(k); });
            aiKeysToRemove.forEach(sources::remove);
            log.info("Purged {} existing ai_-prefixed shapes before merge", aiKeysToRemove.size());

            // Get suppression tolerance
            double suppressionTolerancePct = configService.getDouble("ai_lines.suppression_tolerance_pct", 1.0);

            // Load suppressed list
            List<AiLevelsSuppressed> suppressedList = suppressedRepository.findBySymbol(symbol);

            // Process trendlines
            if (aiLevelsJson.has("trendlines") && aiLevelsJson.get("trendlines").isArray()) {
                for (JsonNode trendline : aiLevelsJson.get("trendlines")) {
                    if (shouldSuppress(trendline, suppressedList, suppressionTolerancePct, "trendline")) {
                        suppressedCount++;
                        suppressedIds.add("trendline_" + trendline.get("name").asText());
                    } else {
                        String lineId = "ai_" + UUID.randomUUID();
                        ObjectNode lineNode = buildTrendlineShape(trendline, lineId, symbol, timeframe);
                        sources.set(lineId, lineNode);
                        mergedCount++;
                    }
                }
            }

            // Process horizontal levels
            if (aiLevelsJson.has("horizontal_levels") && aiLevelsJson.get("horizontal_levels").isArray()) {
                for (JsonNode level : aiLevelsJson.get("horizontal_levels")) {
                    if (shouldSuppressHorizontal(level, suppressedList, suppressionTolerancePct)) {
                        suppressedCount++;
                        suppressedIds.add("h_level_" + level.get("name").asText());
                    } else {
                        String lineId = "ai_" + UUID.randomUUID();
                        ObjectNode lineNode = buildHorizontalShape(level, lineId, symbol, timeframe);
                        sources.set(lineId, lineNode);
                        mergedCount++;
                    }
                }
            }

            // Process fib zones
            if (aiLevelsJson.has("fib_zones") && aiLevelsJson.get("fib_zones").isArray()) {
                for (JsonNode fib : aiLevelsJson.get("fib_zones")) {
                    if (shouldSuppressFib(fib, suppressedList, suppressionTolerancePct)) {
                        suppressedCount++;
                        suppressedIds.add("fib_" + fib.get("name").asText());
                    } else {
                        String lineId = "ai_" + UUID.randomUUID();
                        ObjectNode lineNode = buildFibShape(fib, lineId, symbol, timeframe);
                        sources.set(lineId, lineNode);
                        mergedCount++;
                    }
                }
            }

            // Save updated chart state
            chartState.setOverlaysJson(objectMapper.writeValueAsString(overlaysNode));
            chartState.setTimeframe(timeframe);
            chartStateRepository.save(chartState);

            log.info("Merged AI levels: mergedCount={}, suppressedCount={}", mergedCount, suppressedCount);

            return MergeResult.builder()
                    .mergedCount(mergedCount)
                    .suppressedCount(suppressedCount)
                    .suppressedIds(suppressedIds)
                    .build();

        } catch (Exception e) {
            log.error("Error merging AI drawings", e);
            throw new RuntimeException("Merge failed: " + e.getMessage(), e);
        }
    }

    private ObjectNode buildTrendlineShape(JsonNode trendline, String id, String symbol, String timeframe) {
        // Support both old schema (t0/p0) and new canonical schema (anchor_t0/anchor_p0)
        JsonNode t0Node = trendline.has("anchor_t0") ? trendline.get("anchor_t0") : trendline.get("t0");
        JsonNode p0Node = trendline.has("anchor_p0") ? trendline.get("anchor_p0") : trendline.get("p0");
        JsonNode t1Node = trendline.has("anchor_t1") ? trendline.get("anchor_t1") : trendline.get("t1");
        JsonNode p1Node = trendline.has("anchor_p1") ? trendline.get("anchor_p1") : trendline.get("p1");

        long t0Sec = parseTimestamp(t0Node);
        double p0Price = p0Node.asDouble();
        long t1Sec = parseTimestamp(t1Node);
        double p1Price = p1Node.asDouble();

        String tvInterval = toTvInterval(timeframe);
        ObjectNode props = trendlineProps("#7E57C2", symbol, tvInterval);

        ArrayNode points = objectMapper.createArrayNode();
        points.add(makePoint(t0Sec, p0Price, tvInterval));
        points.add(makePoint(t1Sec, p1Price, tvInterval));

        return wrapTradingViewShape(id, "LineToolTrendLine", props, points, symbol);
    }

    private ObjectNode buildHorizontalShape(JsonNode level, String id, String symbol, String timeframe) {
        double price = level.get("price").asDouble();
        String tvInterval = toTvInterval(timeframe);

        ObjectNode props = horizProps("#26A69A", symbol, tvInterval);

        ArrayNode points = objectMapper.createArrayNode();
        points.add(makePoint(java.time.Instant.now().getEpochSecond(), price, tvInterval));

        return wrapTradingViewShape(id, "LineToolHorzLine", props, points, symbol);
    }

    private ObjectNode buildFibShape(JsonNode fib, String id, String symbol, String timeframe) {
        JsonNode t0Node = fib.has("from_t") ? fib.get("from_t") : fib.get("t0");
        JsonNode p0Node = fib.has("from_p") ? fib.get("from_p") : fib.get("p0");
        JsonNode t1Node = fib.has("to_t") ? fib.get("to_t") : fib.get("t1");
        JsonNode p1Node = fib.has("to_p") ? fib.get("to_p") : fib.get("p1");

        long t0Sec = parseTimestamp(t0Node);
        double p0Price = p0Node.asDouble();
        long t1Sec = parseTimestamp(t1Node);
        double p1Price = p1Node.asDouble();

        String tvInterval = toTvInterval(timeframe);
        // Fib retracement needs at minimum the same base trendline-style props
        ObjectNode props = trendlineProps("#FFA000", symbol, tvInterval);
        props.put("linewidth", 1);

        ArrayNode points = objectMapper.createArrayNode();
        points.add(makePoint(t0Sec, p0Price, tvInterval));
        points.add(makePoint(t1Sec, p1Price, tvInterval));

        return wrapTradingViewShape(id, "LineToolFibRetracement", props, points, symbol);
    }

    /** Common trendline-shape state props. Mirrors TradingView's full schema. */
    private ObjectNode trendlineProps(String color, String symbol, String tvInterval) {
        ObjectNode p = objectMapper.createObjectNode();
        p.put("linecolor", color);
        p.put("linewidth", 1);
        p.put("linestyle", 2);              // dashed (0=solid, 2=dashed)
        p.put("extendLeft", false);
        p.put("extendRight", true);
        p.put("leftEnd", 0);
        p.put("rightEnd", 0);
        p.put("horzLabelsAlign", "center");
        p.put("vertLabelsAlign", "bottom");
        p.put("textcolor", color);
        p.put("fontsize", 12);
        p.put("bold", false);
        p.put("italic", false);
        p.put("alwaysShowStats", false);
        p.put("showMiddlePoint", false);
        p.put("showPriceLabels", false);
        p.put("showPriceRange", false);
        p.put("showPercentPriceRange", false);
        p.put("showPipsPriceRange", false);
        p.put("showBarsRange", false);
        p.put("showDateTimeRange", false);
        p.put("showDistance", false);
        p.put("showAngle", false);
        p.put("statsPosition", 2);
        p.putNull("adjustedToSplitTime");
        p.put("symbolStateVersion", 2);
        p.put("zOrderVersion", 2);
        p.put("visible", true);
        p.put("frozen", false);
        p.put("symbol", symbol);
        p.putNull("currencyId");
        p.putNull("unitId");
        p.set("intervalsVisibilities", intervalsVisibilities());
        p.put("title", "");
        p.put("text", "");
        p.put("interval", tvInterval);
        return p;
    }

    /** Common horizontal-line state props. */
    private ObjectNode horizProps(String color, String symbol, String tvInterval) {
        ObjectNode p = objectMapper.createObjectNode();
        p.put("linecolor", color);
        p.put("linewidth", 1);
        p.put("linestyle", 2);
        p.put("showPrice", true);
        p.put("textcolor", color);
        p.put("fontsize", 12);
        p.put("bold", false);
        p.put("italic", false);
        p.put("horzLabelsAlign", "center");
        p.put("vertLabelsAlign", "middle");
        p.put("symbolStateVersion", 2);
        p.put("zOrderVersion", 2);
        p.put("visible", true);
        p.put("frozen", false);
        p.put("symbol", symbol);
        p.putNull("currencyId");
        p.putNull("unitId");
        p.putNull("adjustedToSplitTime");
        p.set("intervalsVisibilities", intervalsVisibilities());
        p.put("title", "");
        p.put("text", "");
        p.put("interval", tvInterval);
        return p;
    }

    private ObjectNode intervalsVisibilities() {
        ObjectNode iv = objectMapper.createObjectNode();
        iv.put("ticks", true);
        iv.put("seconds", true); iv.put("secondsFrom", 1); iv.put("secondsTo", 59);
        iv.put("minutes", true); iv.put("minutesFrom", 1); iv.put("minutesTo", 59);
        iv.put("hours", true); iv.put("hoursFrom", 1); iv.put("hoursTo", 24);
        iv.put("days", true); iv.put("daysFrom", 1); iv.put("daysTo", 366);
        iv.put("weeks", true); iv.put("weeksFrom", 1); iv.put("weeksTo", 52);
        iv.put("months", true); iv.put("monthsFrom", 1); iv.put("monthsTo", 12);
        iv.put("ranges", true);
        return iv;
    }

    private ObjectNode makePoint(long timeT, double price, String tvInterval) {
        ObjectNode pt = objectMapper.createObjectNode();
        pt.put("time_t", timeT);
        pt.put("offset", 0);
        pt.put("price", price);
        pt.put("interval", tvInterval);
        return pt;
    }

    /** Map our timeframe enum names to TradingView's internal interval strings. */
    private String toTvInterval(String timeframe) {
        if (timeframe == null) return "60";
        switch (timeframe) {
            case "OneMinute": return "1";
            case "ThreeMinute": return "3";
            case "FiveMinute": return "5";
            case "FifteenMinute": return "15";
            case "ThirtyMinute": return "30";
            case "OneHour": return "60";
            case "TwoHour": return "120";
            case "Day": return "1D";
            case "Week": return "1W";
            case "Month": return "1M";
            default: return "60";
        }
    }

    /**
     * Wraps a TradingView line-tool shape in the nested format that getLineToolsState() produces
     * and loadLineToolsAndGroups() expects.
     */
    private ObjectNode wrapTradingViewShape(String id, String tvType, ObjectNode props, ArrayNode points, String symbol) {
        ObjectNode innerState = objectMapper.createObjectNode();
        innerState.put("type", tvType);
        innerState.put("id", id);
        innerState.set("state", props);
        innerState.set("points", points);
        innerState.put("zorder", -19000);  // Below user drawings (which use -20000-ish), still visible
        innerState.put("ownerSource", "_seriesId");
        innerState.put("isSelectionEnabled", true);
        innerState.put("userEditEnabled", true);
        innerState.put("linkKey", randomLinkKey());

        ObjectNode outer = objectMapper.createObjectNode();
        outer.put("id", id);
        outer.put("ownerSource", "_seriesId");
        outer.set("state", innerState);
        outer.put("symbol", symbol);
        outer.putNull("currencyId");
        outer.putNull("unitId");
        return outer;
    }

    private String randomLinkKey() {
        // TV uses short alphanumeric link keys like "Mw4mMWrVW6g7"
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        java.util.Random r = new java.util.Random();
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    private boolean shouldSuppress(JsonNode trendline, List<AiLevelsSuppressed> suppressedList,
                                   double tolerancePct, String lineType) {
        // Support both old schema (p0/p1) and new canonical schema (anchor_p0/anchor_p1)
        JsonNode p0Node = trendline.has("anchor_p0") ? trendline.get("anchor_p0") : trendline.get("p0");
        JsonNode p1Node = trendline.has("anchor_p1") ? trendline.get("anchor_p1") : trendline.get("p1");

        if (p0Node == null || p1Node == null) return false;

        double p0 = p0Node.asDouble();
        double p1 = p1Node.asDouble();

        for (AiLevelsSuppressed suppressed : suppressedList) {
            if (!suppressed.getLineType().equals(lineType)) continue;

            double sup_p0 = suppressed.getAnchorP0().doubleValue();
            double sup_p1 = suppressed.getAnchorP1().doubleValue();

            if (priceWithinTolerance(p0, sup_p0, tolerancePct) &&
                    priceWithinTolerance(p1, sup_p1, tolerancePct)) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldSuppressHorizontal(JsonNode level, List<AiLevelsSuppressed> suppressedList,
                                            double tolerancePct) {
        double price = level.get("price").asDouble();

        for (AiLevelsSuppressed suppressed : suppressedList) {
            if (!suppressed.getLineType().equals("horizontal")) continue;

            double sup_p0 = suppressed.getAnchorP0().doubleValue();

            if (priceWithinTolerance(price, sup_p0, tolerancePct)) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldSuppressFib(JsonNode fib, List<AiLevelsSuppressed> suppressedList,
                                     double tolerancePct) {
        // Support both old schema (p0/p1) and new canonical schema (from_p/to_p)
        JsonNode p0Node = fib.has("from_p") ? fib.get("from_p") : fib.get("p0");
        JsonNode p1Node = fib.has("to_p") ? fib.get("to_p") : fib.get("p1");

        if (p0Node == null || p1Node == null) return false;

        double p0 = p0Node.asDouble();
        double p1 = p1Node.asDouble();

        for (AiLevelsSuppressed suppressed : suppressedList) {
            if (!suppressed.getLineType().equals("fib")) continue;

            double sup_p0 = suppressed.getAnchorP0().doubleValue();
            double sup_p1 = suppressed.getAnchorP1().doubleValue();

            if (priceWithinTolerance(p0, sup_p0, tolerancePct) &&
                    priceWithinTolerance(p1, sup_p1, tolerancePct)) {
                return true;
            }
        }
        return false;
    }

    private boolean priceWithinTolerance(double price1, double price2, double tolerancePct) {
        double tolerance = Math.abs(price2 * tolerancePct / 100.0);
        return Math.abs(price1 - price2) <= tolerance;
    }

    private long parseTimestamp(JsonNode node) {
        if (node.isNumber()) {
            return node.asLong();
        }
        if (node.isTextual()) {
            try {
                return Instant.parse(node.asText()).getEpochSecond();
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid ISO-8601 timestamp: " + node.asText(), e);
            }
        }
        throw new IllegalArgumentException("Timestamp must be number (epoch seconds) or ISO-8601 string, got: " + node.getNodeType());
    }
}
