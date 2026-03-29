package com.dtech.kitecon.screener.elliott.service;

import com.dtech.kitecon.screener.elliott.entity.ElliottTradeSuggestion;
import com.dtech.kitecon.screener.elliott.entity.SuggestionChartLayout;
import com.dtech.kitecon.screener.elliott.repository.SuggestionChartLayoutRepository;
import com.dtech.ta.elliott.ElliottWaveAnalysis;
import com.dtech.ta.elliott.EnrichedPivot;
import com.dtech.ta.trendline.VirginTrendline;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.ta4j.core.BarSeries;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class SuggestionChartLayoutService {

    private final SuggestionChartLayoutRepository layoutRepository;
    private final ObjectMapper objectMapper;
    private final com.dtech.ta.trendline.VirginTrendlineDetector virginTrendlineDetector;

    public void generateAndSaveLayouts(
            ElliottTradeSuggestion suggestion,
            Map<String, ElliottWaveAnalysis> analysisByTf,
            Map<String, BarSeries> seriesByTf) {

        if (suggestion.getId() == null) {
            log.warn("Suggestion has no ID, skipping chart layout generation");
            return;
        }

        // Delete existing layouts and flush so inserts below don't hit duplicate key
        layoutRepository.deleteBySuggestionId(suggestion.getId());
        layoutRepository.flush();

        // Parse all timeframes
        String allTimeframesStr = suggestion.getAllTimeframes();
        if (allTimeframesStr == null || allTimeframesStr.isBlank()) {
            log.warn("Suggestion {} has no allTimeframes set — skipping layout generation", suggestion.getId());
            return;
        }

        List<String> timeframes = Arrays.stream(allTimeframesStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();

        log.info("Generating layouts for suggestion {} timeframes={} primaryTf={}",
            suggestion.getId(), timeframes, suggestion.getPrimaryTimeframe());

        // Generate layouts for each timeframe
        for (int idx = 0; idx < timeframes.size(); idx++) {
            String tf = timeframes.get(idx);
            try {
                ElliottWaveAnalysis analysis = analysisByTf.get(tf);
                BarSeries series = seriesByTf.get(tf);
                boolean isPrimaryTf = tf.equals(suggestion.getPrimaryTimeframe());

                String overlaysJson = buildOverlaysJson(tf, suggestion, analysis, series, isPrimaryTf);

                SuggestionChartLayout layout = SuggestionChartLayout.builder()
                        .suggestionId(suggestion.getId())
                        .timeframe(tf)
                        .tabOrder(idx)
                        .overlaysJson(overlaysJson)
                        .build();

                layoutRepository.save(layout);
            } catch (Exception e) {
                log.error("Failed to generate chart layout for suggestion {} timeframe {}: {}",
                        suggestion.getId(), tf, e.getMessage(), e);
            }
        }
    }

    private String buildOverlaysJson(
            String tf,
            ElliottTradeSuggestion suggestion,
            ElliottWaveAnalysis analysis,
            BarSeries series,
            boolean isPrimaryTf) throws Exception {

        Map<String, List<Map<String, Object>>> overlays = new LinkedHashMap<>();

        overlays.put("hline", buildHLines(suggestion, analysis, series, tf, isPrimaryTf));

        overlays.put("trendline", buildTrendlineOverlays(analysis, series, tf));
        overlays.put("elliott-impulse", buildWaveOverlay(analysis, tf));
        overlays.put("elliott-abc", buildCorrectiveAbcOverlay(analysis, tf));
        overlays.put("elliott-wxyxz", buildComplexCorrectiveOverlay(analysis, tf));

        overlays.put("multi-point-line", new ArrayList<>());

        return objectMapper.writeValueAsString(overlays);
    }

    private List<Map<String, Object>> buildHLines(
            ElliottTradeSuggestion suggestion,
            ElliottWaveAnalysis analysis,
            BarSeries series,
            String tf,
            boolean isPrimaryTf) {

        List<Map<String, Object>> hlines = new ArrayList<>();

        long timeAnchor = (series != null && series.getBarCount() > 0)
                ? series.getBar(series.getBarCount() - 1).getEndTime().getEpochSecond()
                : java.time.Instant.now().getEpochSecond();

        // Entry zone, stop loss, target — primary TF only
        if (isPrimaryTf) {
            String entryZoneStr = suggestion.getEntryZone();
            if (entryZoneStr != null && !entryZoneStr.isBlank()) {
                String trimmed = entryZoneStr.trim();
                Pattern pattern = Pattern.compile("(\\d+\\.?\\d*)\\s*-\\s*(\\d+\\.?\\d*)");
                Matcher matcher = pattern.matcher(trimmed);

                if (matcher.find()) {
                    double low = Double.parseDouble(matcher.group(1));
                    double high = Double.parseDouble(matcher.group(2));

                    hlines.add(buildHLine(timeAnchor, low, "#ffcc80", "dashed", 1));
                    hlines.add(buildHLine(timeAnchor, high, "#ffcc80", "dashed", 1));
                } else {
                    Double singlePrice = parseFirstDouble(trimmed);
                    if (singlePrice != null) {
                        hlines.add(buildHLine(timeAnchor, singlePrice, "#ffcc80", "dashed", 1));
                    }
                }
            }

            String slStr = suggestion.getStopLoss();
            if (slStr != null && !slStr.isBlank()) {
                Double sl = parseFirstDouble(slStr);
                if (sl != null) {
                    hlines.add(buildHLine(timeAnchor, sl, "#ef9a9a", "solid", 2));
                }
            }

            String targetStr = suggestion.getTarget1();
            if (targetStr != null && !targetStr.isBlank()) {
                Double target = parseFirstDouble(targetStr);
                if (target != null) {
                    hlines.add(buildHLine(timeAnchor, target, "#69f0ae", "solid", 2));
                }
            }
        }

        // Gap zones — two hlines per gap (top + bottom)
        if (analysis != null && analysis.getGapsByTf() != null) {
            List<com.dtech.ta.elliott.GapLevel> gaps = analysis.getGapsByTf().get(tf);
            if (gaps != null) {
                long gapAnchor = (series != null && series.getBarCount() > 0)
                        ? series.getBar(series.getBarCount() - 1).getEndTime().getEpochSecond()
                        : java.time.Instant.now().getEpochSecond();
                // Only near-price gaps, rendered as single midpoint line, max 3
                gaps.stream()
                    .filter(g -> g.getFillStatus() != com.dtech.ta.elliott.GapLevel.GapFillStatus.FILLED)
                    .filter(g -> g.isNearCurrentPrice())
                    .sorted(Comparator.comparingInt(com.dtech.ta.elliott.GapLevel::getBarIndex).reversed())
                    .limit(3)
                    .forEach(gap -> {
                        try {
                            boolean gapUp = gap.getDirection() == com.dtech.ta.elliott.GapLevel.GapDirection.GAP_UP;
                            String gapColor = gapUp ? "#66bb6a" : "#ef5350";
                            hlines.add(buildHLine(gapAnchor, gap.getGapMidpoint(), gapColor, "dashed", 1));
                        } catch (Exception e) {
                            log.warn("Failed to add gap hline: {}", e.getMessage());
                        }
                    });
            }
        }

        // Wave key levels: W2 (start of W3), W3 (end of W3), W4 (start of W5), W5 (end of W5)
        if (analysis != null && analysis.getWaveCounts() != null) {
            long waveAnchor = (series != null && series.getBarCount() > 0)
                    ? series.getBar(series.getBarCount() - 1).getEndTime().getEpochSecond()
                    : java.time.Instant.now().getEpochSecond();
            try {
                analysis.getWaveCounts().stream()
                        .filter(wc -> "IMPULSE".equals(wc.getWaveType().toString()))
                        .filter(wc -> tf.equals(wc.getPrimaryTimeframe()))
                        .max(Comparator.comparingDouble(wc -> wc.totalScore()))
                        .ifPresent(topCount -> {
                            var pivots = topCount.getPivots();
                            var pivotToWave = topCount.getPivotToWave();
                            if (pivots == null || pivotToWave == null) return;
                            for (var pivot : pivots) {
                                var label = pivotToWave.get(pivot.getBarIndex());
                                if (label == null) continue;
                                switch (label) {
                                    case W3 -> hlines.add(buildHLine(waveAnchor, pivot.getPrice(), "#2196f3", "dashed", 1));
                                    case W5 -> hlines.add(buildHLine(waveAnchor, pivot.getPrice(), "#42a5f5", "dashed", 1));
                                    default -> {}
                                }
                            }
                        });
            } catch (Exception e) {
                log.warn("Failed to add wave key level hlines for tf {}: {}", tf, e.getMessage());
            }
        }

        // BOS and CHOCH levels from enriched pivots (trend reversal markers)
        if (analysis != null && analysis.getEnrichedPivots() != null) {
            var enrichedPivots = analysis.getEnrichedPivots().get(tf);
            if (enrichedPivots != null) {
                long bosAnchor = (series != null && series.getBarCount() > 0)
                        ? series.getBar(series.getBarCount() - 1).getEndTime().getEpochSecond()
                        : java.time.Instant.now().getEpochSecond();
                // Most recent BOS only (1 line — the key structural break)
                enrichedPivots.stream()
                    .filter(p -> {
                        var sl = p.getStructureLabel();
                        return sl != null && sl.name().startsWith("BOS_");
                    })
                    .max(Comparator.comparingInt(com.dtech.ta.elliott.EnrichedPivot::getBarIndex))
                    .ifPresent(pivot -> {
                        try {
                            hlines.add(buildHLine(bosAnchor, pivot.getPrice(), "#ce93d8", "solid", 2));
                        } catch (Exception e) {
                            log.warn("Failed to add BOS hline: {}", e.getMessage());
                        }
                    });
            }
        }

        return hlines;
    }

    private Map<String, Object> buildHLine(long timeAnchor, double price, String color, String style, int width) {
        Map<String, Object> shape = new LinkedHashMap<>();
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("time", timeAnchor);
        point.put("price", price);
        shape.put("points", List.of(point));

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("color", color);
        props.put("width", width);
        props.put("style", style);
        shape.put("props", props);

        return shape;
    }

    private List<Map<String, Object>> buildTrendlineOverlays(ElliottWaveAnalysis analysis, BarSeries series, String tf) {
        List<Map<String, Object>> trendlines = new ArrayList<>();

        if (analysis == null || series == null) {
            return trendlines;
        }

        // Compute virgin trendlines from structural pivots for this TF
        List<VirginTrendline> vts = new ArrayList<>();
        try {
            if (analysis.getEnrichedPivots() != null) {
                var tfPivots = analysis.getEnrichedPivots().get(tf);
                if (tfPivots != null && !tfPivots.isEmpty()) {
                    vts.addAll(virginTrendlineDetector.detectFromEnrichedPivots(tfPivots, series));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to compute virgin trendlines for tf {}: {}", tf, e.getMessage());
        }

        try {
            int currentBarIndex = series.getBarCount() - 1;
            double currentPrice = series.getBar(currentBarIndex).getClosePrice().doubleValue();

            // Estimate bar duration in seconds for forward projection
            long barDurationSeconds = (currentBarIndex > 0)
                    ? series.getBar(currentBarIndex).getEndTime().getEpochSecond()
                        - series.getBar(currentBarIndex - 1).getEndTime().getEpochSecond()
                    : 86400L;

            for (VirginTrendline vt : vts) {
                try {
                    Map<String, Object> shape = new LinkedHashMap<>();

                    Map<String, Object> p1 = new LinkedHashMap<>();
                    p1.put("time", vt.getAnchor1().getTimestamp().getEpochSecond());
                    p1.put("price", vt.getAnchor1().getPrice());

                    // Check if trendline is within 3×ATR of current price
                    double atr = vt.getAnchor2().getAtrAtPivot();
                    boolean isNearPrice = atr > 0
                            && Math.abs(vt.getPriceAtCurrentBar() - currentPrice) <= 3 * atr;

                    Map<String, Object> p2 = new LinkedHashMap<>();
                    if (isNearPrice) {
                        // Extend 10 bars beyond current bar
                        long futureTime = series.getBar(currentBarIndex).getEndTime().getEpochSecond()
                                + 10 * barDurationSeconds;
                        double futurePrice = vt.getSlope() * (currentBarIndex + 10) + vt.getIntercept();
                        p2.put("time", futureTime);
                        p2.put("price", futurePrice);
                    } else {
                        p2.put("time", vt.getAnchor2().getTimestamp().getEpochSecond());
                        p2.put("price", vt.getAnchor2().getPrice());
                    }

                    shape.put("points", List.of(p1, p2));

                    Map<String, Object> props = new LinkedHashMap<>();
                    // Near-price lines are brighter and solid; distant ones are dimmer and dashed
                    props.put("color", isNearPrice ? "#64b5f6" : "#546e7a");
                    props.put("width", isNearPrice ? 2 : 1);
                    props.put("style", isNearPrice ? "solid" : "dashed");
                    shape.put("props", props);

                    trendlines.add(shape);
                } catch (Exception e) {
                    log.warn("Failed to process virgin trendline: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get virgin trendlines: {}", e.getMessage());
        }

        return trendlines;
    }

    private List<Map<String, Object>> buildWaveOverlay(ElliottWaveAnalysis analysis, String tf) {
        List<Map<String, Object>> waves = new ArrayList<>();

        if (analysis == null) {
            return waves;
        }

        try {
            var waveCounts = analysis.getWaveCounts();
            if (waveCounts == null || waveCounts.isEmpty()) {
                return waves;
            }

            // Find top impulse wave count for this specific timeframe
            var topWaveCount = waveCounts.stream()
                    .filter(wc -> "IMPULSE".equals(wc.getWaveType().toString()))
                    .filter(wc -> tf.equals(wc.getPrimaryTimeframe()))
                    .max(Comparator.comparingDouble(wc -> wc.totalScore()))
                    .orElse(null);

            if (topWaveCount == null) {
                return waves;
            }

            var pivots = topWaveCount.getPivots();
            var pivotToWave = topWaveCount.getPivotToWave();

            if (pivots == null || pivots.isEmpty() || pivotToWave == null) {
                return waves;
            }

            // Build ordered list: origin (first pivot) + W1-W5
            List<Object> points = new ArrayList<>();

            try {
                // Add origin (first pivot)
                var origin = pivots.get(0);
                Map<String, Object> p0 = new LinkedHashMap<>();
                p0.put("time", origin.getTimestamp().getEpochSecond());
                p0.put("price", origin.getPrice());
                points.add(p0);

                // Build a map from wave label to pivot for quick lookup
                // pivotToWave is keyed by bar index, not list index
                Map<String, EnrichedPivot> labelToPivot = new LinkedHashMap<>();
                for (int idx = 0; idx < pivots.size(); idx++) {
                    var pivot = pivots.get(idx);
                    var label = pivotToWave.get(pivot.getBarIndex());
                    if (label != null) {
                        labelToPivot.put(label.toString(), pivot);
                    }
                }

                // Add W1-W5
                for (int i = 1; i <= 5; i++) {
                    var pivot = labelToPivot.get("W" + i);
                    if (pivot == null) {
                        return waves; // Missing wave, abort
                    }
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("time", pivot.getTimestamp().getEpochSecond());
                    p.put("price", pivot.getPrice());
                    points.add(p);
                }

                // Build shape
                Map<String, Object> shape = new LinkedHashMap<>();
                shape.put("points", points);

                Map<String, Object> props = new LinkedHashMap<>();
                props.put("color", "#90caf9");
                props.put("width", 2);
                props.put("style", "solid");
                shape.put("props", props);

                waves.add(shape);
            } catch (Exception e) {
                log.warn("Failed to build wave overlay: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.warn("Failed to process wave analysis: {}", e.getMessage());
        }

        return waves;
    }

    private List<Map<String, Object>> buildCorrectiveAbcOverlay(ElliottWaveAnalysis analysis, String tf) {
        List<Map<String, Object>> shapes = new ArrayList<>();
        if (analysis == null || analysis.getWaveCounts() == null) return shapes;

        // Corrective types handled by elliott-abc (4 points: origin, WA, WB, WC)
        java.util.Set<String> correctiveTypes = java.util.Set.of("ZIGZAG", "FLAT", "EXPANDED_FLAT", "RUNNING_FLAT");

        analysis.getWaveCounts().stream()
                .filter(wc -> correctiveTypes.contains(wc.getWaveType().toString()))
                .filter(wc -> tf.equals(wc.getPrimaryTimeframe()))
                .max(Comparator.comparingDouble(wc -> wc.totalScore()))
                .ifPresent(topCount -> {
                    try {
                        var pivots = topCount.getPivots();
                        var pivotToWave = topCount.getPivotToWave();
                        if (pivots == null || pivotToWave == null || pivots.isEmpty()) return;

                        Map<String, com.dtech.ta.elliott.EnrichedPivot> labelMap = new java.util.LinkedHashMap<>();
                        for (var pivot : pivots) {
                            var label = pivotToWave.get(pivot.getBarIndex());
                            if (label != null) labelMap.put(label.name(), pivot);
                        }

                        // Need origin, WA, WB, WC
                        var origin = pivots.get(0);
                        var wa = labelMap.get("WA");
                        var wb = labelMap.get("WB");
                        var wc = labelMap.get("WC");
                        if (wa == null || wb == null || wc == null) return;

                        List<Object> points = new ArrayList<>();
                        for (var pivot : new com.dtech.ta.elliott.EnrichedPivot[]{origin, wa, wb, wc}) {
                            Map<String, Object> p = new LinkedHashMap<>();
                            p.put("time", pivot.getTimestamp().getEpochSecond());
                            p.put("price", pivot.getPrice());
                            points.add(p);
                        }

                        Map<String, Object> shape = new LinkedHashMap<>();
                        shape.put("points", points);
                        Map<String, Object> props = new LinkedHashMap<>();
                        props.put("color", "#ce93d8");
                        props.put("width", 2);
                        props.put("style", "solid");
                        shape.put("props", props);
                        shapes.add(shape);
                    } catch (Exception e) {
                        log.warn("Failed to build corrective ABC overlay for tf {}: {}", tf, e.getMessage());
                    }
                });

        return shapes;
    }

    private List<Map<String, Object>> buildComplexCorrectiveOverlay(ElliottWaveAnalysis analysis, String tf) {
        List<Map<String, Object>> shapes = new ArrayList<>();
        if (analysis == null || analysis.getWaveCounts() == null) return shapes;

        java.util.Set<String> complexTypes = java.util.Set.of("DOUBLE_ZIGZAG", "COMPLEX_WXY");

        analysis.getWaveCounts().stream()
                .filter(wc -> complexTypes.contains(wc.getWaveType().toString()))
                .filter(wc -> tf.equals(wc.getPrimaryTimeframe()))
                .max(Comparator.comparingDouble(wc -> wc.totalScore()))
                .ifPresent(topCount -> {
                    try {
                        var pivots = topCount.getPivots();
                        var pivotToWave = topCount.getPivotToWave();
                        if (pivots == null || pivotToWave == null || pivots.isEmpty()) return;

                        Map<String, com.dtech.ta.elliott.EnrichedPivot> labelMap = new java.util.LinkedHashMap<>();
                        for (var pivot : pivots) {
                            var label = pivotToWave.get(pivot.getBarIndex());
                            if (label != null) labelMap.put(label.name(), pivot);
                        }

                        // Need origin + WX + WY + WZ (connector + wave + connector + wave) = 6 points
                        // WaveLabel: WX=connector1 end, WY=wave2 end, WZ=connector2 end (for double)
                        // Plugin labels: "0","W","X","Y","X","Z"
                        var origin = pivots.get(0);
                        var wx = labelMap.get("WX");
                        var wy = labelMap.get("WY");
                        var wz = labelMap.get("WZ");
                        if (wx == null || wy == null) return; // need at least WX and WY

                        List<com.dtech.ta.elliott.EnrichedPivot> seq = new ArrayList<>();
                        seq.add(origin);
                        seq.add(wx);
                        seq.add(wy);
                        if (wz != null) {
                            // Pad to 6 points: repeat WY as connector before WZ
                            seq.add(wy);
                            seq.add(wy);
                            seq.add(wz);
                        } else {
                            // Only 3 points — pad to 6 by repeating last
                            seq.add(wy); seq.add(wy); seq.add(wy);
                        }

                        List<Object> points = new ArrayList<>();
                        for (var pivot : seq) {
                            Map<String, Object> p = new LinkedHashMap<>();
                            p.put("time", pivot.getTimestamp().getEpochSecond());
                            p.put("price", pivot.getPrice());
                            points.add(p);
                        }

                        Map<String, Object> shape = new LinkedHashMap<>();
                        shape.put("points", points);
                        Map<String, Object> props = new LinkedHashMap<>();
                        props.put("color", "#80deea");
                        props.put("width", 2);
                        props.put("style", "solid");
                        shape.put("props", props);
                        shapes.add(shape);
                    } catch (Exception e) {
                        log.warn("Failed to build complex corrective overlay for tf {}: {}", tf, e.getMessage());
                    }
                });

        return shapes;
    }

    private List<Map<String, Object>> buildZigzagOverlay(ElliottWaveAnalysis analysis, String tf) {
        List<Map<String, Object>> lines = new ArrayList<>();
        if (analysis == null || analysis.getEnrichedPivots() == null) return lines;

        try {
            List<com.dtech.ta.elliott.EnrichedPivot> pivots = analysis.getEnrichedPivots().get(tf);
            if (pivots == null || pivots.size() < 2) return lines;

            List<Object> points = new ArrayList<>();
            for (com.dtech.ta.elliott.EnrichedPivot pivot : pivots) {
                try {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("time", pivot.getTimestamp().getEpochSecond());
                    p.put("price", pivot.getPrice());
                    points.add(p);
                } catch (Exception e) {
                    log.warn("Failed to add zigzag pivot point: {}", e.getMessage());
                }
            }

            if (points.size() < 2) return lines;

            Map<String, Object> shape = new LinkedHashMap<>();
            shape.put("points", points);
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("color", "#78909c");
            props.put("width", 1);
            props.put("style", "dashed");
            shape.put("props", props);
            lines.add(shape);
        } catch (Exception e) {
            log.warn("Failed to build zigzag overlay for tf {}: {}", tf, e.getMessage());
        }

        return lines;
    }

    private List<Map<String, Object>> buildPatternLevelOverlays(
            ElliottWaveAnalysis analysis, BarSeries series, String tf) {
        List<Map<String, Object>> hlines = new ArrayList<>();
        if (analysis == null || analysis.getAllPatterns() == null) return hlines;

        long timeAnchor = (series != null && series.getBarCount() > 0)
                ? series.getBar(series.getBarCount() - 1).getEndTime().getEpochSecond()
                : java.time.Instant.now().getEpochSecond();

        for (com.dtech.ta.elliott.PatternMatch pattern : analysis.getAllPatterns()) {
            try {
                if (!tf.equals(pattern.getTimeframe())) continue;
                String baseColor = pattern.isBullish() ? "#a5d6a7" : pattern.isBearish() ? "#ef9a9a" : "#ffd54f";

                if (pattern.getNeckline() != null)
                    hlines.add(buildHLine(timeAnchor, pattern.getNeckline(), baseColor, "solid", 2));
                if (pattern.getSupport() != null)
                    hlines.add(buildHLine(timeAnchor, pattern.getSupport(), "#80cbc4", "dashed", 1));
                if (pattern.getResistance() != null)
                    hlines.add(buildHLine(timeAnchor, pattern.getResistance(), "#ffcc80", "dashed", 1));
                if (pattern.getTarget() != null)
                    hlines.add(buildHLine(timeAnchor, pattern.getTarget(), "#69f0ae", "dashed", 1));
                if (pattern.getInvalidation() != null)
                    hlines.add(buildHLine(timeAnchor, pattern.getInvalidation(), "#ef5350", "dashed", 1));
            } catch (Exception e) {
                log.warn("Failed to build pattern level overlays: {}", e.getMessage());
            }
        }

        return hlines;
    }

    private List<Map<String, Object>> buildPatternShapeOverlays(ElliottWaveAnalysis analysis, String tf) {
        List<Map<String, Object>> shapes = new ArrayList<>();
        if (analysis == null || analysis.getAllPatterns() == null) return shapes;

        for (com.dtech.ta.elliott.PatternMatch pattern : analysis.getAllPatterns()) {
            try {
                if (!tf.equals(pattern.getTimeframe())) continue;
                List<java.time.Instant> timestamps = pattern.getPivotTimestamps();
                List<Double> prices = pattern.getPivotPrices();
                if (timestamps == null || prices == null || timestamps.size() < 2) continue;

                List<Object> points = new ArrayList<>();
                int n = Math.min(timestamps.size(), prices.size());
                for (int i = 0; i < n; i++) {
                    if (timestamps.get(i) == null || prices.get(i) == null) continue;
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("time", timestamps.get(i).getEpochSecond());
                    p.put("price", prices.get(i));
                    points.add(p);
                }

                if (points.size() < 2) continue;

                String color = pattern.isBullish() ? "#a5d6a7" : pattern.isBearish() ? "#ef9a9a" : "#ffd54f";
                String style = pattern.getStatus() == com.dtech.ta.elliott.PatternStatus.CONFIRMED ? "solid" : "dashed";

                Map<String, Object> shape = new LinkedHashMap<>();
                shape.put("points", points);
                Map<String, Object> props = new LinkedHashMap<>();
                props.put("color", color);
                props.put("width", 1);
                props.put("style", style);
                shape.put("props", props);
                shapes.add(shape);
            } catch (Exception e) {
                log.warn("Failed to build pattern shape: {}", e.getMessage());
            }
        }

        return shapes;
    }

    private Double parseFirstDouble(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        Pattern pattern = Pattern.compile("[\\d]+\\.?[\\d]*");
        Matcher matcher = pattern.matcher(s);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
