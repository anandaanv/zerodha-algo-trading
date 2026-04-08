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
 * Builds a structured AI prompt for Elliott Wave analysis.
 *
 * Structure:
 *   WAVE COUNTS (best per timeframe)
 *   MARKET STRUCTURE (trend summary per timeframe)
 */
@Service
@Slf4j
public class StructuredPromptBuilder {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("Asia/Kolkata"));
    private static final DateTimeFormatter DATETIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Kolkata"));


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

        // ── Section 1: Wave counts ─────────────────────────────────────────────
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

        // ── Section 2: Market structure ────────────────────────────────────────
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

        // ── Section 3: Nested Corrective Context ──────────────────────────────
        if (analysis != null && analysis.getNestedCorrectiveContexts() != null
                && !analysis.getNestedCorrectiveContexts().isEmpty()) {
            sb.append("=== NESTED CORRECTIVE CONTEXT ===\n");
            for (NestedWaveContext ctx : analysis.getNestedCorrectiveContexts()) {
                sb.append(String.format("[%s] %s → %s | anchor=%.2f inv=%.2f\n",
                        ctx.getTimeframe() != null ? ctx.getTimeframe() : "?",
                        ctx.getHigherDegreeWave() != null ? ctx.getHigherDegreeWave() : "?",
                        ctx.getCorrectiveLeg() != null ? ctx.getCorrectiveLeg() : "?",
                        ctx.getAnchorLevel() != null ? ctx.getAnchorLevel() : 0.0,
                        ctx.getInvalidationLevel() != null ? ctx.getInvalidationLevel() : 0.0));
                if (ctx.getNarrative() != null && !ctx.getNarrative().isBlank()) {
                    String firstLine = ctx.getNarrative().lines().findFirst().orElse("").trim();
                    sb.append("  ").append(firstLine).append("\n");
                }
                if (ctx.getBranches() != null) {
                    for (NestedWaveContext.BranchHypothesis b : ctx.getBranches()) {
                        sb.append(String.format("  [%.0f%%] %s",
                                b.getProbability() * 100,
                                b.getLabel() != null ? b.getLabel() : b.getCode()));
                        if (b.getTargetLevel() != null)
                            sb.append(String.format(" → target=%.2f", b.getTargetLevel()));
                        if (b.getTrigger() != null)
                            sb.append(" | trigger=").append(b.getTrigger());
                        if (b.isTriggered())
                            sb.append(" [TRIGGERED]");
                        sb.append("\n");
                        if (b.getSubwaveLabel() != null)
                            sb.append("    subwave: ").append(b.getSubwaveLabel()).append("\n");
                        if (b.getSubwaveStructureType() != null)
                            sb.append("    structure: ").append(b.getSubwaveStructureType()).append("\n");
                        if (b.getSubwaveNextMove() != null)
                            sb.append("    next: ").append(b.getSubwaveNextMove()).append("\n");
                        if (b.getSubwaveInvalidation() != null)
                            sb.append(String.format("    sub-invalidation: %.2f\n", b.getSubwaveInvalidation()));
                    }
                }
                sb.append("\n");
            }
        }

        // ── Section 4: Top Scenarios ───────────────────────────────────────────
        if (analysis != null && analysis.getScenarios() != null && !analysis.getScenarios().isEmpty()) {
            sb.append("=== TOP SCENARIOS ===\n");
            List<WaveScenario> topScenarios = analysis.getScenarios().stream()
                    .limit(3)
                    .toList();
            for (WaveScenario sc : topScenarios) {
                sb.append(String.format("%s | %s | floor=%.2f\n",
                        sc.getId() != null ? sc.getId() : "?",
                        sc.getDirectionLabel() != null ? sc.getDirectionLabel() : (sc.getDirection() != null ? sc.getDirection().name() : "?"),
                        sc.getScenarioInvalidation()));
                if (sc.getHypotheses() != null) {
                    sc.getHypotheses().stream().limit(3).forEach(h -> {
                        String activation = h.getActivationStatus() != null ? h.getActivationStatus() : "?";
                        if ("DISTANT".equals(activation) || "HISTORICAL".equals(activation)) return;
                        sb.append(String.format("  %s [%s] score=%d",
                                h.getId() != null ? h.getId() : "?",
                                activation,
                                h.getTotalScore()));
                        if (h.getPrimaryTarget() != null)
                            sb.append(String.format(" | target=%.2f(%s)",
                                    h.getPrimaryTarget().getLevel(),
                                    h.getPrimaryTarget().getRatio() != null ? h.getPrimaryTarget().getRatio() : ""));
                        if (h.getInvalidationLevel() != 0)
                            sb.append(String.format(" | inv=%.2f", h.getInvalidationLevel()));
                        if (h.getDecisionLevel() != null)
                            sb.append(String.format(" | decision=%.2f", h.getDecisionLevel()));
                        sb.append("\n");
                        if (h.getCurrentPositionDescription() != null)
                            sb.append("    ").append(h.getCurrentPositionDescription(), 0,
                                    Math.min(100, h.getCurrentPositionDescription().length())).append("\n");
                    });
                }
                sb.append("\n");
            }
        }

        // ── Section 5: Key Price Levels ────────────────────────────────────────
        // Only include levels from the same hypotheses shown in TOP SCENARIOS (top 3 scenarios,
        // top 3 non-DISTANT/non-HISTORICAL hypotheses per scenario).
        if (analysis != null && analysis.getScenarios() != null) {
            sb.append("=== KEY PRICE LEVELS ===\n");
            Set<Double> seen = new java.util.LinkedHashSet<>();
            List<WaveScenario> topScs = analysis.getScenarios().stream().limit(3).toList();
            for (WaveScenario sc : topScs) {
                if (sc.getHypotheses() == null) continue;
                // Mirror the same filter used in TOP SCENARIOS output
                List<WaveHypothesis> visibleHypotheses = sc.getHypotheses().stream()
                        .limit(3)
                        .filter(h -> {
                            String activation = h.getActivationStatus() != null ? h.getActivationStatus() : "?";
                            return !"DISTANT".equals(activation) && !"HISTORICAL".equals(activation);
                        })
                        .toList();
                for (WaveHypothesis h : visibleHypotheses) {
                    if (h.getDecisionLevel() != null && seen.add(h.getDecisionLevel()))
                        sb.append(String.format("  decision  %.2f  (%s)\n",
                                h.getDecisionLevel(),
                                h.getDecisionLevelMeaning() != null ? h.getDecisionLevelMeaning() : "key level"));
                    if (h.getInvalidationLevel() != 0 && seen.add(h.getInvalidationLevel()))
                        sb.append(String.format("  inv       %.2f  (%s)\n",
                                h.getInvalidationLevel(),
                                h.getInvalidationRule() != null ? h.getInvalidationRule() : "invalidation"));
                }
                // Scenario floor for each of the top 3 scenarios
                double floor = sc.getScenarioInvalidation();
                if (floor != 0 && seen.add(floor))
                    sb.append(String.format("  floor     %.2f  (%s floor)\n",
                            floor, sc.getId() != null ? sc.getId() : "scenario"));
            }
            sb.append("\n");
        }

        // ── Section 6: Indicator State ─────────────────────────────────────────
        if (analysis != null && analysis.getEnrichedPivots() != null
                && !analysis.getEnrichedPivots().isEmpty()) {
            sb.append("=== INDICATOR STATE ===\n");
            for (Map.Entry<String, List<EnrichedPivot>> entry : analysis.getEnrichedPivots().entrySet()) {
                String tf = entry.getKey();
                List<EnrichedPivot> pivots = entry.getValue();
                if (pivots == null || pivots.isEmpty()) continue;
                // Use the last pivot (most recent)
                EnrichedPivot last = pivots.get(pivots.size() - 1);
                sb.append(String.format("[%s] RSI=%.1f  EWO=%.1f  MACD_hist=%.2f  ADX=%.1f  BB_pct=%.2f\n",
                        tf,
                        last.getRsi(),
                        last.getEwo(),
                        last.getMacdHistogram(),
                        last.getAdx(),
                        last.getBollingerPctB()));
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
