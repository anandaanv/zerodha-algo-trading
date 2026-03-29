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

    public void generateAndSaveLayouts(
            ElliottTradeSuggestion suggestion,
            Map<String, ElliottWaveAnalysis> analysisByTf,
            Map<String, BarSeries> seriesByTf) {

        if (suggestion.getId() == null) {
            log.warn("Suggestion has no ID, skipping chart layout generation");
            return;
        }

        // Delete existing layouts
        layoutRepository.deleteBySuggestionId(suggestion.getId());

        // Parse all timeframes
        String allTimeframesStr = suggestion.getAllTimeframes();
        if (allTimeframesStr == null || allTimeframesStr.isBlank()) {
            log.warn("Suggestion {} has no timeframes configured", suggestion.getId());
            return;
        }

        List<String> timeframes = Arrays.stream(allTimeframesStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();

        // Generate layouts for each timeframe
        for (int idx = 0; idx < timeframes.size(); idx++) {
            String tf = timeframes.get(idx);
            try {
                ElliottWaveAnalysis analysis = analysisByTf.get(tf);
                BarSeries series = seriesByTf.get(tf);
                boolean isPrimaryTf = tf.equals(suggestion.getPrimaryTimeframe());

                String overlaysJson = buildOverlaysJson(suggestion, analysis, series, isPrimaryTf);

                SuggestionChartLayout layout = SuggestionChartLayout.builder()
                        .suggestionId(suggestion.getId())
                        .timeframe(tf)
                        .tabOrder(idx)
                        .overlaysJson(overlaysJson)
                        .build();

                layoutRepository.save(layout);
            } catch (Exception e) {
                log.warn("Failed to generate chart layout for suggestion {} timeframe {}: {}",
                        suggestion.getId(), tf, e.getMessage());
            }
        }
    }

    private String buildOverlaysJson(
            ElliottTradeSuggestion suggestion,
            ElliottWaveAnalysis analysis,
            BarSeries series,
            boolean isPrimaryTf) throws Exception {

        Map<String, List<Map<String, Object>>> overlays = new LinkedHashMap<>();

        overlays.put("hline", buildHLines(suggestion, series, isPrimaryTf));
        overlays.put("trendline", buildTrendlineOverlays(analysis));
        overlays.put("elliott-impulse", buildWaveOverlay(analysis));

        return objectMapper.writeValueAsString(overlays);
    }

    private List<Map<String, Object>> buildHLines(
            ElliottTradeSuggestion suggestion,
            BarSeries series,
            boolean isPrimaryTf) {

        List<Map<String, Object>> hlines = new ArrayList<>();

        if (!isPrimaryTf || series == null || series.getBarCount() == 0) {
            return hlines;
        }

        long timeAnchor = series.getBar(series.getBarCount() - 1).getEndTime().getEpochSecond();

        // Entry zone
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

        // Stop loss
        String slStr = suggestion.getStopLoss();
        if (slStr != null && !slStr.isBlank()) {
            Double sl = parseFirstDouble(slStr);
            if (sl != null) {
                hlines.add(buildHLine(timeAnchor, sl, "#ef9a9a", "solid", 2));
            }
        }

        // Target 1
        String targetStr = suggestion.getTarget1();
        if (targetStr != null && !targetStr.isBlank()) {
            Double target = parseFirstDouble(targetStr);
            if (target != null) {
                hlines.add(buildHLine(timeAnchor, target, "#69f0ae", "solid", 2));
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

    private List<Map<String, Object>> buildTrendlineOverlays(ElliottWaveAnalysis analysis) {
        List<Map<String, Object>> trendlines = new ArrayList<>();

        if (analysis == null) {
            return trendlines;
        }

        try {
            List<VirginTrendline> vts = analysis.getVirginTrendlines();
            if (vts == null) {
                return trendlines;
            }

            for (VirginTrendline vt : vts) {
                try {
                    Map<String, Object> shape = new LinkedHashMap<>();

                    Map<String, Object> p1 = new LinkedHashMap<>();
                    p1.put("time", vt.getAnchor1().getTimestamp().getEpochSecond());
                    p1.put("price", vt.getAnchor1().getPrice());

                    Map<String, Object> p2 = new LinkedHashMap<>();
                    p2.put("time", vt.getAnchor2().getTimestamp().getEpochSecond());
                    p2.put("price", vt.getAnchor2().getPrice());

                    shape.put("points", List.of(p1, p2));

                    Map<String, Object> props = new LinkedHashMap<>();
                    props.put("color", "#90caf9");
                    props.put("width", 1);
                    props.put("style", "dashed");
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

    private List<Map<String, Object>> buildWaveOverlay(ElliottWaveAnalysis analysis) {
        List<Map<String, Object>> waves = new ArrayList<>();

        if (analysis == null) {
            return waves;
        }

        try {
            var waveCounts = analysis.getWaveCounts();
            if (waveCounts == null || waveCounts.isEmpty()) {
                return waves;
            }

            // Find top impulse wave count
            var topWaveCount = waveCounts.stream()
                    .filter(wc -> "IMPULSE".equals(wc.getWaveType().toString()))
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
                Map<String, EnrichedPivot> labelToPivot = new LinkedHashMap<>();
                for (int idx = 0; idx < pivots.size(); idx++) {
                    var label = pivotToWave.get(idx);
                    if (label != null) {
                        labelToPivot.put(label.toString(), pivots.get(idx));
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
