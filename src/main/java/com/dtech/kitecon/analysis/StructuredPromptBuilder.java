package com.dtech.kitecon.analysis;

import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.ta.elliott.*;
import com.dtech.kitecon.service.copilot.dto.MarketStructureData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Builds a structured, numbered-pivot AI prompt that gives the LLM
 * grounded price/time coordinates for every wave and pattern claim.
 *
 * Structure:
 *   ZIGZAG PIVOTS (numbered Z0, Z1, ... per timeframe)
 *   WAVE COUNTS   (wave endpoints referenced by Zn index)
 *   ACTIVE PATTERNS (only patterns whose price range contains currentPrice)
 *   MARKET STRUCTURE (trend summary per timeframe)
 */
@Service
@Slf4j
public class StructuredPromptBuilder {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("Asia/Kolkata"));
    private static final DateTimeFormatter DATETIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Kolkata"));

    private final PivotHierarchyBuilder hierarchyBuilder;

    public StructuredPromptBuilder(PivotHierarchyBuilder hierarchyBuilder) {
        this.hierarchyBuilder = hierarchyBuilder;
    }

    /**
     * Build the full structured prompt string.
     *
     * @param symbol        instrument symbol
     * @param currentPrice  latest price
     * @param primaryTf     primary timeframe
     * @param pivotsByTf    ordered ZigZag pivots per timeframe (oldest first)
     * @param analysis      full Elliott wave analysis result
     * @param structureByTf market structure per timeframe
     * @return prompt text ready to send to the AI
     */
    public String build(
            String symbol,
            double currentPrice,
            String primaryTf,
            List<String> tfOrder,
            Map<String, List<ZigZagPoint>> pivotsByTf,
            ElliottWaveAnalysis analysis,
            Map<String, MarketStructureData> structureByTf) {

        StringBuilder sb = new StringBuilder();

        // ── Header ────────────────────────────────────────────────────────────
        sb.append("=== SYMBOL: ").append(symbol)
          .append(" | PRICE: ").append(String.format("%.2f", currentPrice))
          .append(" | PRIMARY TF: ").append(primaryTf)
          .append(" ===\n\n");

        // ── Section 1: Nested ZigZag pivot hierarchy ──────────────────────────────
        // Build waveLabels map for inline annotation in pivots
        Map<String, Map<Integer, String>> waveLabels = new LinkedHashMap<>();
        if (analysis != null && analysis.getWaveCounts() != null && !analysis.getWaveCounts().isEmpty()) {
            // Pick highest-scoring WaveCount per timeframe
            Map<String, WaveCount> bestByTf = new LinkedHashMap<>();
            for (WaveCount wc : analysis.getWaveCounts()) {
                String tf = wc.getPrimaryTimeframe();
                if (tf == null) continue;
                WaveCount cur = bestByTf.get(tf);
                if (cur == null || wc.totalScore() > cur.totalScore()) bestByTf.put(tf, wc);
            }
            // For each best WaveCount, build the barIndex → label mapping
            for (Map.Entry<String, WaveCount> entry : bestByTf.entrySet()) {
                String tf = entry.getKey();
                WaveCount wc = entry.getValue();
                Map<Integer, String> tfWaveLabels = new LinkedHashMap<>();
                waveLabels.put(tf, tfWaveLabels);
                if (wc.getPivots() != null && wc.getPivotToWave() != null) {
                    for (EnrichedPivot ep : wc.getPivots()) {
                        WaveLabel label = wc.getPivotToWave().get(ep.getBarIndex());
                        if (label != null) {
                            tfWaveLabels.put(ep.getBarIndex(), label.name());
                        }
                    }
                }
            }
        }

        String pivotHierarchy = hierarchyBuilder.build(pivotsByTf, tfOrder, currentPrice, waveLabels);
        sb.append("=== ZIGZAG PIVOTS ===\n");
        sb.append(pivotHierarchy);
        sb.append("\n");

        // Rebuild barIndexToZn and tsEpochToZn for pattern/wave matching (flat maps still needed)
        Map<String, Map<Integer, Integer>> barIndexToZn = new LinkedHashMap<>();
        Map<String, Map<Long, Integer>>    tsEpochToZn  = new LinkedHashMap<>();
        for (Map.Entry<String, List<ZigZagPoint>> entry : pivotsByTf.entrySet()) {
            String tf = entry.getKey();
            List<ZigZagPoint> pivots = entry.getValue();
            if (pivots == null || pivots.isEmpty()) continue;
            Map<Integer, Integer> barMap = new LinkedHashMap<>();
            Map<Long, Integer>    tsMap  = new LinkedHashMap<>();
            barIndexToZn.put(tf, barMap);
            tsEpochToZn.put(tf, tsMap);
            for (int i = 0; i < pivots.size(); i++) {
                ZigZagPoint p = pivots.get(i);
                barMap.put(p.getBarIndex(), i);
                if (p.getTimestamp() != null) tsMap.put(p.getTimestamp().getEpochSecond(), i);
            }
        }

        // ── Section 2: Wave counts ─────────────────────────────────────────────
        if (analysis != null && analysis.getWaveCounts() != null && !analysis.getWaveCounts().isEmpty()) {
            sb.append("=== WAVE COUNTS (best per timeframe) ===\n");

            // Pick highest-scoring WaveCount per timeframe
            Map<String, WaveCount> bestByTf = new LinkedHashMap<>();
            for (WaveCount wc : analysis.getWaveCounts()) {
                String tf = wc.getPrimaryTimeframe();
                if (tf == null) continue;
                WaveCount cur = bestByTf.get(tf);
                if (cur == null || wc.totalScore() > cur.totalScore()) bestByTf.put(tf, wc);
            }

            for (Map.Entry<String, WaveCount> entry : bestByTf.entrySet()) {
                String tf = entry.getKey();
                WaveCount wc = entry.getValue();

                sb.append(String.format("[%s] %s %s  score=%d\n",
                        tf,
                        wc.getWaveType() != null ? wc.getWaveType().name() : "?",
                        wc.isBullish() ? "BULLISH" : "BEARISH",
                        wc.totalScore()));

                if (wc.getCurrentPositionDescription() != null) {
                    sb.append("  Current: ").append(wc.getCurrentPositionDescription()).append("\n");
                }
                sb.append("\n");
            }
        }

        // ── Section 3: Active patterns (containing currentPrice) ──────────────
        if (analysis != null && analysis.getAllPatterns() != null) {
            List<PatternMatch> active = filterActivePatterns(analysis.getAllPatterns(), currentPrice);
            if (!active.isEmpty()) {
                sb.append("=== ACTIVE PATTERNS (current price ").append(String.format("%.2f", currentPrice)).append(" is within range) ===\n");
                for (PatternMatch p : active) {
                    String tf = p.getTimeframe() != null ? p.getTimeframe() : "?";
                    Map<Long, Integer> tsMap = tsEpochToZn.getOrDefault(tf, Map.of());

                    sb.append(String.format("[%s] %s  %s  conf=%.0f%%\n",
                            tf,
                            p.getType() != null ? p.getType().name() : "?",
                            p.getStatus() != null ? p.getStatus().name() : "?",
                            p.getConfidence()));

                    // Pivot references
                    if (p.getPivotTimestamps() != null && !p.getPivotTimestamps().isEmpty()
                            && p.getPivotPrices() != null) {
                        sb.append("  Pivots: ");
                        for (int i = 0; i < p.getPivotTimestamps().size(); i++) {
                            var ts = p.getPivotTimestamps().get(i);
                            Double price = i < p.getPivotPrices().size() ? p.getPivotPrices().get(i) : null;
                            Integer zn = ts != null ? tsMap.get(ts.getEpochSecond()) : null;
                            String znStr = zn != null ? "Z" + zn : "Z?";
                            String dateStr = ts != null ? (isIntradayTf(tf) ? DATETIME_FMT.format(ts) : DATE_FMT.format(ts)) : "?";
                            sb.append(znStr);
                            if (price != null) sb.append(String.format("(%.2f", price));
                            sb.append(",").append(dateStr).append(")");
                            if (i < p.getPivotTimestamps().size() - 1) sb.append(" → ");
                        }
                        sb.append("\n");
                    }

                    // Key levels
                    if (p.getSupport() != null)    sb.append(String.format("  Support:    %.2f\n", p.getSupport()));
                    if (p.getResistance() != null) sb.append(String.format("  Resistance: %.2f\n", p.getResistance()));
                    if (p.getNeckline() != null)   sb.append(String.format("  Neckline:   %.2f\n", p.getNeckline()));
                    if (p.getTarget() != null)     sb.append(String.format("  Target:     %.2f\n", p.getTarget()));
                    if (p.getInvalidation() != null) sb.append(String.format("  Invalidation: %.2f\n", p.getInvalidation()));

                    // Trendline geometry
                    if (Boolean.TRUE.equals(p.isConverging())) sb.append("  Trendlines: CONVERGING\n");
                    else if (Boolean.TRUE.equals(p.isParallel())) sb.append("  Trendlines: PARALLEL (channel)\n");

                    // Wave context
                    if (p.getWaveContextHints() != null && !p.getWaveContextHints().isEmpty()) {
                        sb.append("  Wave context: ");
                        for (WaveContextHint h : p.getWaveContextHints()) {
                            sb.append(h.getImpliedCurrentPosition()).append("→")
                              .append(h.getImpliedNextWave())
                              .append(String.format("(%.0f%%)", h.getProbability() * 100)).append(" ");
                        }
                        sb.append("\n");
                    }

                    if (p.getDescription() != null) sb.append("  ").append(p.getDescription()).append("\n");
                    sb.append("\n");
                }
            } else {
                sb.append("=== ACTIVE PATTERNS ===\n  None containing current price ").append(String.format("%.2f", currentPrice)).append("\n\n");
            }
        }

        // ── Section 4: Market structure ────────────────────────────────────────
        if (structureByTf != null && !structureByTf.isEmpty()) {
            sb.append("=== MARKET STRUCTURE ===\n");
            for (Map.Entry<String, MarketStructureData> entry : structureByTf.entrySet()) {
                String summary = entry.getValue().toPromptSummary();
                if (summary != null && !summary.isBlank()) {
                    sb.append("[").append(entry.getKey()).append("] ")
                      .append(summary.replace("\n", " ").trim()).append("\n");
                }
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Keep only patterns where currentPrice is within the support–resistance envelope.
     * Excludes INVALIDATED patterns. Allows 10% tolerance beyond the range.
     */
    private List<PatternMatch> filterActivePatterns(List<PatternMatch> all, double currentPrice) {
        List<PatternMatch> result = new ArrayList<>();
        for (PatternMatch p : all) {
            if (p.getStatus() == PatternStatus.CONFIRMED
                    || p.getStatus() == PatternStatus.INVALIDATED) continue;

            Double support    = p.getSupport();
            Double resistance = p.getResistance();

            if (support != null && resistance != null) {
                double lo = Math.min(support, resistance);
                double hi = Math.max(support, resistance);
                double tol = (hi - lo) * 0.10;
                if (currentPrice >= (lo - tol) && currentPrice <= (hi + tol)) {
                    result.add(p);
                }
            } else if (support != null) {
                // Only a floor — include if price is above it (within 15%)
                if (currentPrice >= support * 0.85) result.add(p);
            } else if (resistance != null) {
                // Only a ceiling — include if price is below it (within 15%)
                if (currentPrice <= resistance * 1.15) result.add(p);
            } else {
                // No price bounds — include BUILDING/WATCHING only
                String statusName = p.getStatus() != null ? p.getStatus().name() : "";
                if (statusName.equals("BUILDING") || statusName.equals("WATCHING")) {
                    result.add(p);
                }
            }
        }
        return result;
    }

    private boolean isIntradayTf(String tf) {
        if (tf == null) return false;
        String lo = tf.toLowerCase();
        return lo.contains("min") || lo.contains("h") || lo.equals("30m") || lo.equals("15m");
    }
}
