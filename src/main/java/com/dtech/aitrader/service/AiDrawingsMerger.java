package com.dtech.aitrader.service;

import com.dtech.aitrader.data.AiLevelsSuppressed;
import com.dtech.aitrader.repository.AiLevelsSuppressedRepository;
import com.dtech.kitecon.data.UserChartState;
import com.dtech.kitecon.repository.UserChartStateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
                        ObjectNode lineNode = buildTrendlineShape(trendline, lineId);
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
                        ObjectNode lineNode = buildHorizontalShape(level, lineId);
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
                        ObjectNode lineNode = buildFibShape(fib, lineId);
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

    private ObjectNode buildTrendlineShape(JsonNode trendline, String id) {
        ObjectNode shape = objectMapper.createObjectNode();
        shape.put("type", "trend");
        shape.put("id", id);

        ObjectNode state = objectMapper.createObjectNode();
        state.put("linecolor", "#7E57C2");  // Purple
        state.put("linestyle", "2");         // Dashed
        state.put("linewidth", "1");

        // Support both old schema (t0/p0) and new canonical schema (anchor_t0/anchor_p0)
        JsonNode t0Node = trendline.has("anchor_t0") ? trendline.get("anchor_t0") : trendline.get("t0");
        JsonNode p0Node = trendline.has("anchor_p0") ? trendline.get("anchor_p0") : trendline.get("p0");
        JsonNode t1Node = trendline.has("anchor_t1") ? trendline.get("anchor_t1") : trendline.get("t1");
        JsonNode p1Node = trendline.has("anchor_p1") ? trendline.get("anchor_p1") : trendline.get("p1");

        long t0Sec = parseTimestamp(t0Node);
        double p0Price = p0Node.asDouble();
        long t1Sec = parseTimestamp(t1Node);
        double p1Price = p1Node.asDouble();

        ObjectNode points = objectMapper.createObjectNode();
        ObjectNode p0 = objectMapper.createObjectNode();
        p0.put("time_t", t0Sec);
        p0.put("price", p0Price);

        ObjectNode p1 = objectMapper.createObjectNode();
        p1.put("time_t", t1Sec);
        p1.put("price", p1Price);

        points.set("0", p0);
        points.set("1", p1);

        shape.set("points", points);
        shape.set("state", state);

        return shape;
    }

    private ObjectNode buildHorizontalShape(JsonNode level, String id) {
        ObjectNode shape = objectMapper.createObjectNode();
        shape.put("type", "horizontal");
        shape.put("id", id);

        ObjectNode state = objectMapper.createObjectNode();
        state.put("linecolor", "#26A69A");  // Teal
        state.put("linestyle", "2");        // Dashed
        state.put("linewidth", "1");

        ObjectNode points = objectMapper.createObjectNode();
        ObjectNode p = objectMapper.createObjectNode();
        p.put("price", level.get("price").asDouble());

        points.set("0", p);
        shape.set("points", points);
        shape.set("state", state);

        return shape;
    }

    private ObjectNode buildFibShape(JsonNode fib, String id) {
        ObjectNode shape = objectMapper.createObjectNode();
        shape.put("type", "fib");
        shape.put("id", id);

        ObjectNode state = objectMapper.createObjectNode();
        state.put("linecolor", "#FFA000");  // Amber
        state.put("linestyle", "2");
        state.put("linewidth", "1");

        // Support both old schema (t0/p0) and new canonical schema (from_t/from_p, to_t/to_p)
        JsonNode t0Node = fib.has("from_t") ? fib.get("from_t") : fib.get("t0");
        JsonNode p0Node = fib.has("from_p") ? fib.get("from_p") : fib.get("p0");
        JsonNode t1Node = fib.has("to_t") ? fib.get("to_t") : fib.get("t1");
        JsonNode p1Node = fib.has("to_p") ? fib.get("to_p") : fib.get("p1");

        long t0Sec = parseTimestamp(t0Node);
        double p0Price = p0Node.asDouble();
        long t1Sec = parseTimestamp(t1Node);
        double p1Price = p1Node.asDouble();

        ObjectNode points = objectMapper.createObjectNode();
        ObjectNode p0 = objectMapper.createObjectNode();
        p0.put("time_t", t0Sec);
        p0.put("price", p0Price);

        ObjectNode p1 = objectMapper.createObjectNode();
        p1.put("time_t", t1Sec);
        p1.put("price", p1Price);

        points.set("0", p0);
        points.set("1", p1);

        shape.set("points", points);
        shape.set("state", state);

        return shape;
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
