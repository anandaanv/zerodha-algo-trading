package com.dtech.ta.elliott;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Layer 6: Cross-timeframe Elliott Wave counter.
 *
 * Given enriched pivots for multiple timeframes (ordered parent→child),
 * generates ALL valid wave counts as competing candidates.
 *
 * Design principle: generates possibilities, draws no conclusions.
 * Every valid count is returned regardless of confidence.
 * Scoring happens here; ranking happens in ScenarioBuilder.
 */
@Service
public class WaveCounter {

    // ── Fibonacci ratio tolerance (±X% from ideal) ────────────────────────────
    private static final double FIB_TOLERANCE = 0.15;

    // ── Ideal Fibonacci ratios for impulse waves ──────────────────────────────
    private static final double W2_IDEAL_RETRACE   = 0.618;
    private static final double W3_IDEAL_EXTENSION = 1.618;
    private static final double W4_IDEAL_RETRACE   = 0.382;

    // ── Corrective ratios ──────────────────────────────────────────────────────
    private static final double B_ZIGZAG_MAX    = 0.786;
    private static final double B_FLAT_MIN      = 0.900;
    private static final double C_ZIGZAG_MIN    = 0.618;
    private static final double C_ZIGZAG_MAX    = 1.618;

    /**
     * Generate all valid wave counts across timeframes.
     *
     * @param pivotsByTf  map of timeframe → enriched pivots (e.g., "1d" → [...], "1h" → [...])
     * @param tfOrder     ordered list of timeframes from highest to lowest (e.g., ["1w","1d","1h","15m"])
     * @return all valid wave counts, unsorted
     */
    public List<WaveCount> generateCounts(Map<String, List<EnrichedPivot>> pivotsByTf, List<String> tfOrder) {
        List<WaveCount> allCounts = new ArrayList<>();

        // Analyse each timeframe independently
        for (String tf : tfOrder) {
            List<EnrichedPivot> pivots = pivotsByTf.get(tf);
            if (pivots == null || pivots.size() < 3) continue;

            // Try to fit impulse patterns (5 waves)
            allCounts.addAll(scanImpulse(pivots, tf));

            // Try corrective patterns (3-wave and 5-wave triangle) on recent sub-sections
            allCounts.addAll(scanCorrections(pivots, tf));
        }

        // Post-impulse: for complete impulses, scan subsequent pivots for ABC correction
        List<WaveCount> postCorrs = new ArrayList<>();
        for (WaveCount wc : new ArrayList<>(allCounts)) {
            if (wc.getWaveType() != WaveCount.WaveType.IMPULSE
                    || wc.getCurrentWaveInProgress() != WaveLabel.UNKNOWN
                    || wc.getPivots() == null || wc.getPivots().isEmpty()) continue;
            EnrichedPivot w5End = wc.getPivots().get(wc.getPivots().size() - 1);
            List<EnrichedPivot> tfPivots = pivotsByTf.get(wc.getPrimaryTimeframe());
            if (tfPivots == null) continue;
            int w5Idx = tfPivots.indexOf(w5End);
            if (w5Idx < 0 || w5Idx + 2 >= tfPivots.size()) continue;
            List<EnrichedPivot> afterImpulse = tfPivots.subList(w5Idx, tfPivots.size());
            if (afterImpulse.get(0).isHigh()) {
                WaveCount corr = tryFitZigzagDown(afterImpulse, 0, wc.getPrimaryTimeframe());
                if (corr != null) {
                    corr.setCurrentPositionDescription("Post-impulse ABC after W5 at "
                        + fmt(w5End.getPrice()) + " — " + corr.getCurrentPositionDescription());
                    postCorrs.add(corr);
                }
            }
        }
        allCounts.addAll(postCorrs);

        // Structural-only pass: re-scan without Fibonacci filtering to catch extended W3 patterns
        // These get structuralOnly=true and are only included if no Fibonacci count covers the same pivots
        List<WaveCount> structuralCandidates = new ArrayList<>();
        for (String tf : tfOrder) {
            List<EnrichedPivot> pivots = pivotsByTf.get(tf);
            if (pivots == null || pivots.size() < 6) continue;
            structuralCandidates.addAll(scanImpulseStructuralOnly(pivots, tf));
        }
        // De-duplicate: remove structural candidates whose W1-start and W5-end match an existing count
        for (WaveCount sc : structuralCandidates) {
            boolean duplicate = allCounts.stream().anyMatch(existing ->
                !existing.isStructuralOnly()
                && existing.getPivots() != null && sc.getPivots() != null
                && existing.getPivots().size() >= 2 && sc.getPivots().size() >= 2
                && existing.getPivots().get(0).getBarIndex() == sc.getPivots().get(0).getBarIndex()
                && existing.getPivots().get(existing.getPivots().size() - 1).getBarIndex()
                   == sc.getPivots().get(sc.getPivots().size() - 1).getBarIndex()
            );
            if (!duplicate) allCounts.add(sc);
        }

        // Cross-TF validation: boost scores where parent/child TF structures agree
        if (tfOrder.size() >= 2) {
            crossTfValidate(allCounts, pivotsByTf, tfOrder);
        }

        // Remove hard rule violations
        allCounts.removeIf(wc -> !wc.isValid());

        return allCounts;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // IMPULSE WAVE DETECTION
    // ══════════════════════════════════════════════════════════════════════════

    private List<WaveCount> scanImpulse(List<EnrichedPivot> pivots, String tf) {
        List<WaveCount> results = new ArrayList<>();

        // Look for sequences of pivots that could form W1-W5
        // We need at minimum 3 pivots to infer partial impulse (W1-W2-partial W3)
        // and up to 5+ pivots for complete impulse
        for (int start = 0; start < pivots.size() - 2; start++) {
            EnrichedPivot p0 = pivots.get(start);

            // Impulse UP: starts from a LOW, ends on a HIGH
            if (p0.isLow()) {
                WaveCount up = tryFitImpulseUp(pivots, start, tf);
                if (up != null) results.add(up);
                WaveCount diagUp = tryFitDiagonalUp(pivots, start, tf);
                if (diagUp != null) results.add(diagUp);
            }

            // Impulse DOWN: starts from a HIGH, ends on a LOW
            if (p0.isHigh()) {
                WaveCount down = tryFitImpulseDown(pivots, start, tf);
                if (down != null) results.add(down);
                WaveCount diagDown = tryFitDiagonalDown(pivots, start, tf);
                if (diagDown != null) results.add(diagDown);
            }
        }
        return results;
    }

    private WaveCount tryFitImpulseUp(List<EnrichedPivot> pivots, int startIdx, String tf) {
        // Minimum pivots needed: 6 (low-high-low-high-low-high = W0 base, W1-end, W2-end, W3-end, W4-end, W5-end)
        // But we also handle partial (in-progress) impulses
        List<EnrichedPivot> remaining = pivots.subList(startIdx, pivots.size());
        List<EnrichedPivot> lows  = remaining.stream().filter(EnrichedPivot::isLow).toList();
        List<EnrichedPivot> highs = remaining.stream().filter(EnrichedPivot::isHigh).toList();

        if (highs.isEmpty() || lows.isEmpty()) return null;

        EnrichedPivot w1Start = remaining.get(0);   // LOW = impulse start
        EnrichedPivot w1End   = highs.get(0);       // First HIGH = W1 end
        if (w1End.getPrice() <= w1Start.getPrice()) return null;

        double w1Length = w1End.getPrice() - w1Start.getPrice();

        // Significance check: W1 must be meaningful relative to full price range
        // If W1 is < 8% of the total range in remaining pivots, this is likely a micro-wave miscount
        double totalRange = remaining.stream().mapToDouble(EnrichedPivot::getPrice).max().orElse(0)
                          - remaining.stream().mapToDouble(EnrichedPivot::getPrice).min().orElse(0);
        double w1Significance = totalRange > 0 ? w1Length / totalRange : 1.0;
        // Low significance does NOT eliminate — it reduces the fibonacci score
        int significancePenalty = w1Significance < 0.08 ? -15 : w1Significance < 0.15 ? -8 : 0;

        // W2: first LOW after W1 end — must not retrace 100% of W1
        EnrichedPivot w2End = firstLowAfter(remaining, w1End.getBarIndex());
        if (w2End == null) {
            // In-progress: W1 complete, W2 not yet formed
            return buildPartialImpulse(w1Start, w1End, null, null, null, null, tf,
                    "W1 complete, awaiting W2 correction");
        }
        double w2Retrace = (w1End.getPrice() - w2End.getPrice()) / w1Length;
        if (w2Retrace >= 1.0) {
            return null; // Hard rule: W2 cannot retrace 100% of W1
        }
        if (w2End.getPrice() <= w1Start.getPrice()) return null; // Rule violation

        // W3: first HIGH after W2 — must be greater than W1 end
        EnrichedPivot w3End = firstHighAfter(remaining, w2End.getBarIndex());
        if (w3End == null) {
            return buildPartialImpulse(w1Start, w1End, w2End, null, null, null, tf,
                    "W2 complete, awaiting W3 impulse");
        }
        if (w3End.getPrice() <= w1End.getPrice()) return null; // W3 must exceed W1

        double w3Length = w3End.getPrice() - w2End.getPrice();

        // W4: first LOW after W3 — must not overlap W1 territory (W4 low > W1 high)
        EnrichedPivot w4End = firstLowAfter(remaining, w3End.getBarIndex());
        if (w4End == null) {
            return buildPartialImpulse(w1Start, w1End, w2End, w3End, null, null, tf,
                    "W3 complete, awaiting W4 correction");
        }
        if (w4End.getPrice() <= w1End.getPrice()) {
            // Hard rule: W4 cannot enter W1 territory (diagonal exception handled separately)
            List<String> violations = List.of("W4 low " + fmt(w4End.getPrice())
                    + " overlaps W1 high " + fmt(w1End.getPrice()));
            return buildImpulseWithViolations(w1Start, w1End, w2End, w3End, w4End, null, tf, violations);
        }

        double w4Retrace = (w3End.getPrice() - w4End.getPrice()) / w3Length;

        // W5: first HIGH after W4 — must exceed W3
        EnrichedPivot w5End = firstHighAfter(remaining, w4End.getBarIndex());
        if (w5End == null) {
            return buildPartialImpulse(w1Start, w1End, w2End, w3End, w4End, null, tf,
                    "W4 complete, awaiting W5 final impulse");
        }
        if (w5End.getPrice() <= w3End.getPrice() && w5End.getPrice() <= w1End.getPrice()) {
            // Could be truncated W5 — still valid but note it
        }

        // Hard rule: W3 must not be the shortest wave
        double w5Length = w5End.getPrice() - w4End.getPrice();
        List<String> violations = new ArrayList<>();
        if (w3Length < w1Length && w3Length < w5Length) {
            violations.add("W3 is the shortest wave (W1=" + fmt(w1Length) + " W3=" + fmt(w3Length)
                    + " W5=" + fmt(w5Length) + ")");
        }

        // Compute scores
        int fibScore      = scoreFibImpulseUp(w1Length, w2Retrace, w3Length, w4Retrace, w5Length);
        // Hard rule violation: zero Fibonacci score if W3 is shortest
        if (!violations.isEmpty()) fibScore = 0;
        int indicatorScore = scoreIndicatorsImpulseUp(w1End, w2End, w3End, w4End, w5End);
        boolean w5Truncated = w5End.getPrice() < w3End.getPrice();

        // Evidence
        List<WaveEvidence> supporting    = new ArrayList<>();
        List<WaveEvidence> contradicting = new ArrayList<>();
        gatherImpulseEvidence(w1Start, w1End, w2End, w3End, w4End, w5End,
                w2Retrace, w3Length / w1Length, w4Retrace, supporting, contradicting);

        Map<Integer, WaveLabel> labels = new LinkedHashMap<>();
        labels.put(w1Start.getBarIndex(), WaveLabel.W1);  // W1 start is the base
        labels.put(w1End.getBarIndex(),   WaveLabel.W1);
        labels.put(w2End.getBarIndex(),   WaveLabel.W2);
        labels.put(w3End.getBarIndex(),   WaveLabel.W3);
        labels.put(w4End.getBarIndex(),   WaveLabel.W4);
        labels.put(w5End.getBarIndex(),   WaveLabel.W5);

        return WaveCount.builder()
                .waveType(WaveCount.WaveType.IMPULSE)
                .primaryTimeframe(tf)
                .bullish(true)
                .pivotToWave(labels)
                .pivots(List.of(w1Start, w1End, w2End, w3End, w4End, w5End))
                .currentWaveInProgress(WaveLabel.UNKNOWN)  // impulse complete
                .currentPositionDescription("Impulse complete " + fmt(w1Start.getPrice())
                        + "→" + fmt(w5End.getPrice()) + (w5Truncated ? " (truncated W5)" : ""))
                .fibonacciScore(fibScore + significancePenalty)
                .indicatorScore(indicatorScore)
                .crossTfScore(0)
                .alternationScore(0)
                .wave2RetracePct(w2Retrace)
                .wave3ExtensionPct(w3Length / w1Length)
                .wave4RetracePct(w4Retrace)
                .ewoW3Peak(w3End.getEwo() > w1End.getEwo() && w3End.getEwo() > w5End.getEwo())
                .finalWaveDivergence(w5End.getMacdHistogram() < w3End.getMacdHistogram()
                        || w5End.getEwo() < w3End.getEwo())
                .wave3VolumeExpansion(w3End.getVolume() > w1End.getVolume() * 1.1)
                .wave3BollingerWalk(w3End.isBollingerWalk())
                .supportingEvidence(supporting)
                .contradictingEvidence(contradicting)
                .ruleViolations(violations)
                .build();
    }

    private WaveCount tryFitImpulseDown(List<EnrichedPivot> pivots, int startIdx, String tf) {
        List<EnrichedPivot> remaining = pivots.subList(startIdx, pivots.size());
        List<EnrichedPivot> lows  = remaining.stream().filter(EnrichedPivot::isLow).toList();
        List<EnrichedPivot> highs = remaining.stream().filter(EnrichedPivot::isHigh).toList();

        if (lows.isEmpty() || highs.isEmpty()) return null;

        EnrichedPivot w1Start = remaining.get(0);
        EnrichedPivot w1End   = lows.get(0);
        if (w1End.getPrice() >= w1Start.getPrice()) return null;
        double w1Length = w1Start.getPrice() - w1End.getPrice();

        // Significance penalty (same logic as impulse up)
        double totalRange = remaining.stream().mapToDouble(EnrichedPivot::getPrice).max().orElse(0)
                          - remaining.stream().mapToDouble(EnrichedPivot::getPrice).min().orElse(0);
        double w1Significance = totalRange > 0 ? w1Length / totalRange : 1.0;
        int significancePenalty = w1Significance < 0.08 ? -15 : w1Significance < 0.15 ? -8 : 0;

        EnrichedPivot w2End = firstHighAfter(remaining, w1End.getBarIndex());
        if (w2End == null) return buildPartialImpulseDown(w1Start, w1End, null, null, null, null, tf, "W1 down complete");

        double w2Retrace = (w2End.getPrice() - w1End.getPrice()) / w1Length;
        if (w2Retrace >= 1.0) return null;
        if (w2End.getPrice() >= w1Start.getPrice()) return null;

        EnrichedPivot w3End = firstLowAfter(remaining, w2End.getBarIndex());
        if (w3End == null) return buildPartialImpulseDown(w1Start, w1End, w2End, null, null, null, tf, "W2 up complete");
        if (w3End.getPrice() >= w1End.getPrice()) return null;
        double w3Length = w2End.getPrice() - w3End.getPrice();

        EnrichedPivot w4End = firstHighAfter(remaining, w3End.getBarIndex());
        if (w4End == null) return buildPartialImpulseDown(w1Start, w1End, w2End, w3End, null, null, tf, "W3 down complete");
        if (w4End.getPrice() >= w1End.getPrice()) {
            return buildImpulseDownWithViolations(w1Start, w1End, w2End, w3End, w4End, null, tf,
                    List.of("W4 high overlaps W1 low territory"));
        }
        double w4Retrace = (w4End.getPrice() - w3End.getPrice()) / w3Length;

        EnrichedPivot w5End = firstLowAfter(remaining, w4End.getBarIndex());
        if (w5End == null) return buildPartialImpulseDown(w1Start, w1End, w2End, w3End, w4End, null, tf, "W4 up complete");

        double w5Length = w4End.getPrice() - w5End.getPrice();
        List<String> violations = new ArrayList<>();
        if (w3Length < w1Length && w3Length < w5Length) {
            violations.add("W3 is shortest wave");
        }

        int fibScore      = scoreFibImpulseDown(w1Length, w2Retrace, w3Length, w4Retrace, w5Length);
        // Hard rule violation: zero Fibonacci score if W3 is shortest
        if (!violations.isEmpty()) fibScore = 0;
        int indicatorScore = scoreIndicatorsImpulseDown(w1End, w2End, w3End, w4End, w5End);

        Map<Integer, WaveLabel> labels = new LinkedHashMap<>();
        labels.put(w1Start.getBarIndex(), WaveLabel.W1);
        labels.put(w1End.getBarIndex(),   WaveLabel.W1);
        labels.put(w2End.getBarIndex(),   WaveLabel.W2);
        labels.put(w3End.getBarIndex(),   WaveLabel.W3);
        labels.put(w4End.getBarIndex(),   WaveLabel.W4);
        labels.put(w5End.getBarIndex(),   WaveLabel.W5);

        return WaveCount.builder()
                .waveType(WaveCount.WaveType.IMPULSE)
                .primaryTimeframe(tf)
                .bullish(false)
                .pivotToWave(labels)
                .pivots(List.of(w1Start, w1End, w2End, w3End, w4End, w5End))
                .currentWaveInProgress(WaveLabel.UNKNOWN)
                .currentPositionDescription("Bearish impulse " + fmt(w1Start.getPrice())
                        + "→" + fmt(w5End.getPrice()))
                .fibonacciScore(fibScore + significancePenalty)
                .indicatorScore(indicatorScore)
                .crossTfScore(0)
                .alternationScore(0)
                .wave2RetracePct(w2Retrace)
                .wave3ExtensionPct(w3Length / w1Length)
                .wave4RetracePct(w4Retrace)
                .ewoW3Peak(w3End.getEwo() < w1End.getEwo() && w3End.getEwo() < w5End.getEwo())
                .finalWaveDivergence(w5End.getMacdHistogram() > w3End.getMacdHistogram())
                .supportingEvidence(new ArrayList<>())
                .contradictingEvidence(new ArrayList<>())
                .ruleViolations(violations)
                .build();
    }

    private WaveCount tryFitDiagonalUp(List<EnrichedPivot> pivots, int startIdx, String tf) {
        List<EnrichedPivot> remaining = pivots.subList(startIdx, pivots.size());
        List<EnrichedPivot> lows  = remaining.stream().filter(EnrichedPivot::isLow).toList();
        List<EnrichedPivot> highs = remaining.stream().filter(EnrichedPivot::isHigh).toList();
        if (highs.size() < 3 || lows.size() < 2) return null;

        EnrichedPivot w1Start = remaining.get(0);
        EnrichedPivot w1End   = highs.get(0);
        if (w1End.getPrice() <= w1Start.getPrice()) return null;
        double w1Length = w1End.getPrice() - w1Start.getPrice();

        EnrichedPivot w2End = firstLowAfter(remaining, w1End.getBarIndex());
        if (w2End == null) return null;
        double w2Retrace = (w1End.getPrice() - w2End.getPrice()) / w1Length;
        if (w2Retrace >= 1.0 || w2Retrace < 0.50) return null;  // W2: 50–100% retrace
        if (w2End.getPrice() <= w1Start.getPrice()) return null;

        EnrichedPivot w3End = firstHighAfter(remaining, w2End.getBarIndex());
        if (w3End == null) return null;
        if (w3End.getPrice() <= w1End.getPrice()) return null;  // W3 must exceed W1 end
        double w3Length = w3End.getPrice() - w2End.getPrice();

        EnrichedPivot w4End = firstLowAfter(remaining, w3End.getBarIndex());
        if (w4End == null) return null;
        // KEY DIAGNOSTIC: W4 must overlap W1 territory (drop below W1 end)
        if (w4End.getPrice() >= w1End.getPrice()) return null;
        double w4Retrace = (w3End.getPrice() - w4End.getPrice()) / w3Length;
        if (w4Retrace >= 1.0) return null;

        EnrichedPivot w5End = firstHighAfter(remaining, w4End.getBarIndex());
        if (w5End == null) {
            // Partial diagonal — W5 not yet formed
            Map<Integer, WaveLabel> labels = new LinkedHashMap<>();
            labels.put(w1Start.getBarIndex(), WaveLabel.W1);
            labels.put(w1End.getBarIndex(),   WaveLabel.W1);
            labels.put(w2End.getBarIndex(),   WaveLabel.W2);
            labels.put(w3End.getBarIndex(),   WaveLabel.W3);
            labels.put(w4End.getBarIndex(),   WaveLabel.W4);
            WaveCount.WaveType diagType = classifyDiagonal(pivots, startIdx, true);
            return WaveCount.builder()
                    .waveType(diagType).primaryTimeframe(tf).bullish(true)
                    .pivotToWave(labels)
                    .pivots(List.of(w1Start, w1End, w2End, w3End, w4End))
                    .currentWaveInProgress(WaveLabel.W5)
                    .currentPositionDescription(diagType.name() + " up — W4 overlaps W1, awaiting W5 completion")
                    .fibonacciScore(10).indicatorScore(0).crossTfScore(0).alternationScore(0)
                    .supportingEvidence(new ArrayList<>()).contradictingEvidence(new ArrayList<>())
                    .ruleViolations(new ArrayList<>()).build();
        }

        double w5Length = w5End.getPrice() - w4End.getPrice();

        // Contracting check: W1 > W3 > W5 (diagonal rule — not a hard violation but scored)
        List<WaveEvidence> supporting    = new ArrayList<>();
        List<WaveEvidence> contradicting = new ArrayList<>();
        if (w1Length > w3Length && w3Length > w5Length) {
            supporting.add(WaveEvidence.supports("Structure", "Contracting diagonal: W1>W3>W5"));
        } else {
            contradicting.add(WaveEvidence.contradicts("Structure", "Non-contracting diagonal: wave lengths not strictly decreasing"));
        }

        // Trendline convergence check
        boolean converging = trendlinesConverge(w1End, w3End, w2End, w4End, true);
        if (converging) {
            supporting.add(WaveEvidence.supports("Trendline", "W1-W3 and W2-W4 trendlines converge (wedge shape)"));
        } else {
            contradicting.add(WaveEvidence.contradicts("Trendline", "Trendlines do not converge — expanding diagonal or mislabeled"));
        }

        int fibScore      = scoreFibDiagonal(w1Length, w2Retrace, w3Length, w4Retrace, w5Length, converging);
        int indicatorScore = scoreIndicatorsImpulseUp(w1End, w2End, w3End, w4End, w5End);

        Map<Integer, WaveLabel> labels2 = new LinkedHashMap<>();
        labels2.put(w1Start.getBarIndex(), WaveLabel.W1);
        labels2.put(w1End.getBarIndex(),   WaveLabel.W1);
        labels2.put(w2End.getBarIndex(),   WaveLabel.W2);
        labels2.put(w3End.getBarIndex(),   WaveLabel.W3);
        labels2.put(w4End.getBarIndex(),   WaveLabel.W4);
        labels2.put(w5End.getBarIndex(),   WaveLabel.W5);

        WaveCount.WaveType diagType2 = classifyDiagonal(pivots, startIdx, true);

        return WaveCount.builder()
                .waveType(diagType2).primaryTimeframe(tf).bullish(true)
                .pivotToWave(labels2)
                .pivots(List.of(w1Start, w1End, w2End, w3End, w4End, w5End))
                .currentWaveInProgress(WaveLabel.UNKNOWN)
                .currentPositionDescription(diagType2.name() + " up [W4 overlaps W1] "
                        + fmt(w1Start.getPrice()) + "→" + fmt(w5End.getPrice())
                        + (converging ? " (converging wedge)" : " (expanding)"))
                .fibonacciScore(fibScore)
                .indicatorScore(indicatorScore)
                .crossTfScore(0).alternationScore(0)
                .wave2RetracePct(w2Retrace)
                .wave3ExtensionPct(w3Length / w1Length)
                .wave4RetracePct(w4Retrace)
                .finalWaveDivergence(w5End.getMacdHistogram() < w3End.getMacdHistogram()
                        || w5End.getEwo() < w3End.getEwo())
                .supportingEvidence(supporting).contradictingEvidence(contradicting)
                .ruleViolations(new ArrayList<>())
                .build();
    }

    private WaveCount tryFitDiagonalDown(List<EnrichedPivot> pivots, int startIdx, String tf) {
        List<EnrichedPivot> remaining = pivots.subList(startIdx, pivots.size());
        List<EnrichedPivot> lows  = remaining.stream().filter(EnrichedPivot::isLow).toList();
        List<EnrichedPivot> highs = remaining.stream().filter(EnrichedPivot::isHigh).toList();
        if (lows.size() < 3 || highs.size() < 2) return null;

        EnrichedPivot w1Start = remaining.get(0);
        EnrichedPivot w1End   = lows.get(0);
        if (w1End.getPrice() >= w1Start.getPrice()) return null;
        double w1Length = w1Start.getPrice() - w1End.getPrice();

        EnrichedPivot w2End = firstHighAfter(remaining, w1End.getBarIndex());
        if (w2End == null) return null;
        double w2Retrace = (w2End.getPrice() - w1End.getPrice()) / w1Length;
        if (w2Retrace >= 1.0 || w2Retrace < 0.50) return null;
        if (w2End.getPrice() >= w1Start.getPrice()) return null;

        EnrichedPivot w3End = firstLowAfter(remaining, w2End.getBarIndex());
        if (w3End == null) return null;
        if (w3End.getPrice() >= w1End.getPrice()) return null;  // W3 must break below W1 end
        double w3Length = w2End.getPrice() - w3End.getPrice();

        EnrichedPivot w4End = firstHighAfter(remaining, w3End.getBarIndex());
        if (w4End == null) return null;
        // KEY DIAGNOSTIC: W4 must overlap W1 territory (rise above W1 end)
        if (w4End.getPrice() <= w1End.getPrice()) return null;
        double w4Retrace = (w4End.getPrice() - w3End.getPrice()) / w3Length;
        if (w4Retrace >= 1.0) return null;

        EnrichedPivot w5End = firstLowAfter(remaining, w4End.getBarIndex());
        if (w5End == null) {
            Map<Integer, WaveLabel> labels = new LinkedHashMap<>();
            labels.put(w1Start.getBarIndex(), WaveLabel.W1);
            labels.put(w1End.getBarIndex(),   WaveLabel.W1);
            labels.put(w2End.getBarIndex(),   WaveLabel.W2);
            labels.put(w3End.getBarIndex(),   WaveLabel.W3);
            labels.put(w4End.getBarIndex(),   WaveLabel.W4);
            WaveCount.WaveType diagType = classifyDiagonal(pivots, startIdx, false);
            return WaveCount.builder()
                    .waveType(diagType).primaryTimeframe(tf).bullish(false)
                    .pivotToWave(labels)
                    .pivots(List.of(w1Start, w1End, w2End, w3End, w4End))
                    .currentWaveInProgress(WaveLabel.W5)
                    .currentPositionDescription(diagType.name() + " down — W4 overlaps W1, awaiting W5 completion")
                    .fibonacciScore(10).indicatorScore(0).crossTfScore(0).alternationScore(0)
                    .supportingEvidence(new ArrayList<>()).contradictingEvidence(new ArrayList<>())
                    .ruleViolations(new ArrayList<>()).build();
        }

        double w5Length = w4End.getPrice() - w5End.getPrice();

        List<WaveEvidence> supporting    = new ArrayList<>();
        List<WaveEvidence> contradicting = new ArrayList<>();
        if (w1Length > w3Length && w3Length > w5Length) {
            supporting.add(WaveEvidence.supports("Structure", "Contracting diagonal: W1>W3>W5"));
        } else {
            contradicting.add(WaveEvidence.contradicts("Structure", "Non-contracting diagonal"));
        }

        boolean converging = trendlinesConverge(w1End, w3End, w2End, w4End, false);
        if (converging) {
            supporting.add(WaveEvidence.supports("Trendline", "W1-W3 and W2-W4 trendlines converge (wedge shape)"));
        } else {
            contradicting.add(WaveEvidence.contradicts("Trendline", "Trendlines do not converge"));
        }

        int fibScore      = scoreFibDiagonal(w1Length, w2Retrace, w3Length, w4Retrace, w5Length, converging);
        int indicatorScore = scoreIndicatorsImpulseDown(w1End, w2End, w3End, w4End, w5End);

        Map<Integer, WaveLabel> labels2 = new LinkedHashMap<>();
        labels2.put(w1Start.getBarIndex(), WaveLabel.W1);
        labels2.put(w1End.getBarIndex(),   WaveLabel.W1);
        labels2.put(w2End.getBarIndex(),   WaveLabel.W2);
        labels2.put(w3End.getBarIndex(),   WaveLabel.W3);
        labels2.put(w4End.getBarIndex(),   WaveLabel.W4);
        labels2.put(w5End.getBarIndex(),   WaveLabel.W5);

        WaveCount.WaveType diagType2 = classifyDiagonal(pivots, startIdx, false);

        return WaveCount.builder()
                .waveType(diagType2).primaryTimeframe(tf).bullish(false)
                .pivotToWave(labels2)
                .pivots(List.of(w1Start, w1End, w2End, w3End, w4End, w5End))
                .currentWaveInProgress(WaveLabel.UNKNOWN)
                .currentPositionDescription(diagType2.name() + " down [W4 overlaps W1] "
                        + fmt(w1Start.getPrice()) + "→" + fmt(w5End.getPrice())
                        + (converging ? " (converging wedge)" : " (expanding)"))
                .fibonacciScore(fibScore)
                .indicatorScore(indicatorScore)
                .crossTfScore(0).alternationScore(0)
                .wave2RetracePct(w2Retrace)
                .wave3ExtensionPct(w3Length / w1Length)
                .wave4RetracePct(w4Retrace)
                .finalWaveDivergence(w5End.getMacdHistogram() > w3End.getMacdHistogram())
                .supportingEvidence(supporting).contradictingEvidence(contradicting)
                .ruleViolations(new ArrayList<>())
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CORRECTIVE WAVE DETECTION (ZIGZAG, FLAT, TRIANGLE)
    // ══════════════════════════════════════════════════════════════════════════

    private List<WaveCount> scanCorrections(List<EnrichedPivot> pivots, String tf) {
        List<WaveCount> results = new ArrayList<>();

        // Only look at recent half of pivots — corrections are current context
        int startFrom = Math.max(0, pivots.size() - 20);

        for (int i = startFrom; i < pivots.size() - 1; i++) {
            EnrichedPivot p = pivots.get(i);

            // Zigzag / Flat after a high (downward correction)
            if (p.isHigh()) {
                WaveCount zz = tryFitZigzagDown(pivots, i, tf);
                if (zz != null) results.add(zz);
                WaveCount flat = tryFitFlatDown(pivots, i, tf);
                if (flat != null) results.add(flat);
            }

            // Zigzag / Flat after a low (upward correction in bearish context, or bull rebound)
            if (p.isLow()) {
                WaveCount zz = tryFitZigzagUp(pivots, i, tf);
                if (zz != null) results.add(zz);
            }

            // Double Zigzag: WXY after a high (downward)
            if (p.isHigh()) {
                WaveCount dz = tryFitDoubleZigzagDown(pivots, i, tf);
                if (dz != null) results.add(dz);
            }
            // Double Zigzag: WXY after a low (upward)
            if (p.isLow()) {
                WaveCount dz = tryFitDoubleZigzagUp(pivots, i, tf);
                if (dz != null) results.add(dz);
            }
        }

        // Triangle: look for RANGING structure in recent pivots
        WaveCount tri = tryFitTriangle(pivots, tf);
        if (tri != null) results.add(tri);

        return results;
    }

    /** Downward Zigzag: HIGH → LOW (A) → HIGH (B, <78.6% retrace of A) → LOW (C) */
    private WaveCount tryFitZigzagDown(List<EnrichedPivot> pivots, int startIdx, String tf) {
        EnrichedPivot wA_start = pivots.get(startIdx);
        EnrichedPivot wA_end   = firstLowAfter(pivots, wA_start.getBarIndex());
        if (wA_end == null) {
            return buildZigzagBuilding(wA_start, null, null, null, tf, "A-wave forming");
        }

        double aLength = wA_start.getPrice() - wA_end.getPrice();
        if (aLength <= 0) return null;

        EnrichedPivot wB_end = firstHighAfter(pivots, wA_end.getBarIndex());
        if (wB_end == null) {
            return buildZigzagBuilding(wA_start, wA_end, null, null, tf, "B-wave forming");
        }

        double bRetrace = (wB_end.getPrice() - wA_end.getPrice()) / aLength;
        if (bRetrace > 1.0) return null;  // B cannot exceed A start

        WaveCount.WaveType type;
        if (bRetrace <= B_ZIGZAG_MAX) {
            type = WaveCount.WaveType.ZIGZAG;
        } else if (bRetrace >= B_FLAT_MIN) {
            type = WaveCount.WaveType.FLAT;
        } else {
            return null;  // Ambiguous zone 0.786–0.900
        }

        EnrichedPivot wC_end = firstLowAfter(pivots, wB_end.getBarIndex());
        if (wC_end == null) {
            return buildCorrectionBuilding(wA_start, wA_end, wB_end, null, type, tf, "C-wave forming");
        }

        double cLength = wB_end.getPrice() - wC_end.getPrice();
        double cToA    = cLength / aLength;

        List<WaveEvidence> supporting    = new ArrayList<>();
        List<WaveEvidence> contradicting = new ArrayList<>();

        if (cToA >= C_ZIGZAG_MIN && cToA <= C_ZIGZAG_MAX)
            supporting.add(WaveEvidence.supports("Fibonacci", "C/A ratio " + fmt(cToA) + " within 0.618–1.618", cToA));
        else
            contradicting.add(WaveEvidence.contradicts("Fibonacci", "C/A ratio " + fmt(cToA) + " outside expected range", cToA));

        boolean ewoDiv = wC_end.getEwo() > wA_end.getEwo();
        if (ewoDiv) supporting.add(WaveEvidence.supports("EWO", "Bullish divergence at Wave C vs Wave A"));

        boolean rsiDiv = wC_end.getRsi() > wA_end.getRsi();
        if (rsiDiv) supporting.add(WaveEvidence.supports("RSI", "RSI divergence at Wave C"));

        int fibScore = scoreFibCorrection(bRetrace, cToA, type);

        Map<Integer, WaveLabel> labels = new LinkedHashMap<>();
        labels.put(wA_start.getBarIndex(), WaveLabel.WA);
        labels.put(wA_end.getBarIndex(),   WaveLabel.WA);
        labels.put(wB_end.getBarIndex(),   WaveLabel.WB);
        labels.put(wC_end.getBarIndex(),   WaveLabel.WC);

        return WaveCount.builder()
                .waveType(type)
                .primaryTimeframe(tf)
                .bullish(false)
                .pivotToWave(labels)
                .pivots(List.of(wA_start, wA_end, wB_end, wC_end))
                .currentWaveInProgress(WaveLabel.UNKNOWN)
                .currentPositionDescription(type.name() + " correction " + fmt(wA_start.getPrice())
                        + "→A:" + fmt(wA_end.getPrice()) + " B:" + fmt(wB_end.getPrice())
                        + " C:" + fmt(wC_end.getPrice()))
                .fibonacciScore(fibScore)
                .indicatorScore((ewoDiv ? 8 : 0) + (rsiDiv ? 7 : 0))
                .crossTfScore(0)
                .alternationScore(0)
                .waveBRetracePct(bRetrace)
                .finalWaveDivergence(ewoDiv)
                .supportingEvidence(supporting)
                .contradictingEvidence(contradicting)
                .ruleViolations(new ArrayList<>())
                .build();
    }

    private WaveCount tryFitZigzagUp(List<EnrichedPivot> pivots, int startIdx, String tf) {
        EnrichedPivot wA_start = pivots.get(startIdx);
        EnrichedPivot wA_end   = firstHighAfter(pivots, wA_start.getBarIndex());
        if (wA_end == null) return buildZigzagBuilding(wA_start, null, null, null, tf, "A-wave up forming");

        double aLength = wA_end.getPrice() - wA_start.getPrice();
        if (aLength <= 0) return null;

        EnrichedPivot wB_end = firstLowAfter(pivots, wA_end.getBarIndex());
        if (wB_end == null) return buildZigzagBuilding(wA_start, wA_end, null, null, tf, "B-wave down forming");

        double bRetrace = (wA_end.getPrice() - wB_end.getPrice()) / aLength;
        if (bRetrace > 1.0) return null;
        WaveCount.WaveType type;
        if (bRetrace <= B_ZIGZAG_MAX) {
            type = WaveCount.WaveType.ZIGZAG;
        } else if (bRetrace >= B_FLAT_MIN) {
            type = WaveCount.WaveType.FLAT;
        } else {
            return null;
        }

        EnrichedPivot wC_end = firstHighAfter(pivots, wB_end.getBarIndex());
        if (wC_end == null) return buildCorrectionBuilding(wA_start, wA_end, wB_end, null, type, tf, "C-wave up forming");

        Map<Integer, WaveLabel> labels = new LinkedHashMap<>();
        labels.put(wA_start.getBarIndex(), WaveLabel.WA);
        labels.put(wA_end.getBarIndex(),   WaveLabel.WA);
        labels.put(wB_end.getBarIndex(),   WaveLabel.WB);
        labels.put(wC_end.getBarIndex(),   WaveLabel.WC);

        return WaveCount.builder()
                .waveType(type)
                .primaryTimeframe(tf)
                .bullish(true)
                .pivotToWave(labels)
                .pivots(List.of(wA_start, wA_end, wB_end, wC_end))
                .currentWaveInProgress(WaveLabel.UNKNOWN)
                .currentPositionDescription("Bullish " + type.name() + " " + fmt(wA_start.getPrice())
                        + "→C:" + fmt(wC_end.getPrice()))
                .fibonacciScore(scoreFibCorrection(bRetrace, (wC_end.getPrice() - wB_end.getPrice()) / aLength, type))
                .indicatorScore(0)
                .crossTfScore(0)
                .alternationScore(0)
                .waveBRetracePct(bRetrace)
                .supportingEvidence(new ArrayList<>())
                .contradictingEvidence(new ArrayList<>())
                .ruleViolations(new ArrayList<>())
                .build();
    }

    private WaveCount tryFitFlatDown(List<EnrichedPivot> pivots, int startIdx, String tf) {
        // Flat: B retraces ≥ 90% of A — handled in tryFitZigzagDown's type discrimination
        // This method handles expanded flats (B > A start)
        EnrichedPivot wA_start = pivots.get(startIdx);
        EnrichedPivot wA_end   = firstLowAfter(pivots, wA_start.getBarIndex());
        if (wA_end == null) return null;
        double aLength = wA_start.getPrice() - wA_end.getPrice();
        if (aLength <= 0) return null;

        EnrichedPivot wB_end = firstHighAfter(pivots, wA_end.getBarIndex());
        if (wB_end == null) return null;
        double bRetrace = (wB_end.getPrice() - wA_end.getPrice()) / aLength;

        // Expanded flat: B exceeds A's origin
        if (bRetrace <= 1.0) return null;  // Not expanded — handled elsewhere

        EnrichedPivot wC_end = firstLowAfter(pivots, wB_end.getBarIndex());
        if (wC_end == null) return buildCorrectionBuilding(wA_start, wA_end, wB_end, null,
                WaveCount.WaveType.EXPANDED_FLAT, tf, "C-wave of expanded flat forming");

        Map<Integer, WaveLabel> labels = new LinkedHashMap<>();
        labels.put(wA_start.getBarIndex(), WaveLabel.WA);
        labels.put(wA_end.getBarIndex(),   WaveLabel.WA);
        labels.put(wB_end.getBarIndex(),   WaveLabel.WB);
        labels.put(wC_end.getBarIndex(),   WaveLabel.WC);

        return WaveCount.builder()
                .waveType(WaveCount.WaveType.EXPANDED_FLAT)
                .primaryTimeframe(tf)
                .bullish(false)
                .pivotToWave(labels)
                .pivots(List.of(wA_start, wA_end, wB_end, wC_end))
                .currentWaveInProgress(WaveLabel.UNKNOWN)
                .currentPositionDescription("Expanded Flat: B exceeded A start (B retrace=" + fmt(bRetrace * 100) + "%)")
                .fibonacciScore(15)
                .indicatorScore(0)
                .crossTfScore(0)
                .alternationScore(0)
                .waveBRetracePct(bRetrace)
                .supportingEvidence(new ArrayList<>(List.of(WaveEvidence.supports("Structure", "B exceeds A origin — expanded flat signature"))))
                .contradictingEvidence(new ArrayList<>())
                .ruleViolations(new ArrayList<>())
                .build();
    }

    /**
     * Double Zigzag Down: WX (first zigzag down) + connector W (retrace up) + WY (second zigzag down).
     * WY must extend beyond WX end. Connector retraces 25–61.8% of WX.
     */
    private WaveCount tryFitDoubleZigzagDown(List<EnrichedPivot> pivots, int startIdx, String tf) {
        EnrichedPivot wxStart = pivots.get(startIdx);
        // WX: first zigzag down — find WA (low), WB (high <78.6% retrace), WC (low)
        EnrichedPivot wxA = firstLowAfter(pivots, wxStart.getBarIndex());
        if (wxA == null) return null;
        double wxALen = wxStart.getPrice() - wxA.getPrice();
        if (wxALen <= 0) return null;

        EnrichedPivot wxB = firstHighAfter(pivots, wxA.getBarIndex());
        if (wxB == null) return null;
        double wxBRetrace = (wxB.getPrice() - wxA.getPrice()) / wxALen;
        if (wxBRetrace > B_ZIGZAG_MAX) return null;  // WX must be a zigzag

        EnrichedPivot wxC = firstLowAfter(pivots, wxB.getBarIndex());
        if (wxC == null) return null;
        double wxLength = wxStart.getPrice() - wxC.getPrice();
        if (wxLength <= 0) return null;

        // Connector W: retrace up 25–61.8% of WX length
        EnrichedPivot connectorEnd = firstHighAfter(pivots, wxC.getBarIndex());
        if (connectorEnd == null) return null;
        double connectorRetrace = (connectorEnd.getPrice() - wxC.getPrice()) / wxLength;
        if (connectorRetrace < 0.25 || connectorRetrace > 0.618) return null;

        // WY: second zigzag down — must extend beyond wxC
        EnrichedPivot wyA = firstLowAfter(pivots, connectorEnd.getBarIndex());
        if (wyA == null) {
            // Building: WY not yet started
            Map<Integer, WaveLabel> labels = new LinkedHashMap<>();
            labels.put(wxStart.getBarIndex(), WaveLabel.WX);
            labels.put(wxC.getBarIndex(),     WaveLabel.WX);
            labels.put(connectorEnd.getBarIndex(), WaveLabel.WY);
            return WaveCount.builder()
                    .waveType(WaveCount.WaveType.DOUBLE_ZIGZAG).primaryTimeframe(tf).bullish(false)
                    .pivotToWave(labels).pivots(List.of(wxStart, wxC, connectorEnd))
                    .currentWaveInProgress(WaveLabel.WY)
                    .currentPositionDescription("Double Zigzag down — WX complete, connector done, WY forming")
                    .fibonacciScore(8).indicatorScore(0).crossTfScore(0).alternationScore(0)
                    .supportingEvidence(new ArrayList<>()).contradictingEvidence(new ArrayList<>())
                    .ruleViolations(new ArrayList<>()).build();
        }

        EnrichedPivot wyB = firstHighAfter(pivots, wyA.getBarIndex());
        if (wyB == null) return null;
        double wyALen = connectorEnd.getPrice() - wyA.getPrice();
        if (wyALen <= 0) return null;
        double wyBRetrace = (wyB.getPrice() - wyA.getPrice()) / wyALen;
        if (wyBRetrace > B_ZIGZAG_MAX) return null;

        EnrichedPivot wyC = firstLowAfter(pivots, wyB.getBarIndex());
        if (wyC == null) {
            Map<Integer, WaveLabel> labels = new LinkedHashMap<>();
            labels.put(wxStart.getBarIndex(), WaveLabel.WX);
            labels.put(wxC.getBarIndex(),     WaveLabel.WX);
            labels.put(connectorEnd.getBarIndex(), WaveLabel.WY);
            labels.put(wyB.getBarIndex(),     WaveLabel.WC);
            return WaveCount.builder()
                    .waveType(WaveCount.WaveType.DOUBLE_ZIGZAG).primaryTimeframe(tf).bullish(false)
                    .pivotToWave(labels).pivots(List.of(wxStart, wxC, connectorEnd, wyB))
                    .currentWaveInProgress(WaveLabel.WC)
                    .currentPositionDescription("Double Zigzag down — WY C-wave forming")
                    .fibonacciScore(10).indicatorScore(0).crossTfScore(0).alternationScore(0)
                    .supportingEvidence(new ArrayList<>()).contradictingEvidence(new ArrayList<>())
                    .ruleViolations(new ArrayList<>()).build();
        }

        // WY must extend beyond WX end (wyC < wxC)
        if (wyC.getPrice() >= wxC.getPrice()) return null;

        double wyLength = connectorEnd.getPrice() - wyC.getPrice();
        double wyToWx = wyLength / wxLength;

        List<WaveEvidence> supporting = new ArrayList<>();
        List<WaveEvidence> contradicting = new ArrayList<>();
        if (connectorRetrace >= 0.382 && connectorRetrace <= 0.618)
            supporting.add(WaveEvidence.supports("Fibonacci", "Connector retraces " + fmt(connectorRetrace * 100) + "% of WX (ideal 38.2–61.8%)"));
        if (wyToWx >= 0.9 && wyToWx <= 1.1)
            supporting.add(WaveEvidence.supports("Fibonacci", "WY ≈ WX (equality ratio " + fmt(wyToWx) + ")"));
        else
            contradicting.add(WaveEvidence.contradicts("Fibonacci", "WY/WX ratio " + fmt(wyToWx) + " outside ideal 0.9–1.1"));

        int fibScore = 0;
        fibScore += fibProximityScore(connectorRetrace, 0.500, 10);
        fibScore += fibProximityScore(wyToWx, 1.0, 12);

        Map<Integer, WaveLabel> labels = new LinkedHashMap<>();
        labels.put(wxStart.getBarIndex(),       WaveLabel.WX);
        labels.put(wxC.getBarIndex(),           WaveLabel.WX);
        labels.put(connectorEnd.getBarIndex(),  WaveLabel.WY);
        labels.put(wyC.getBarIndex(),           WaveLabel.WY);

        return WaveCount.builder()
                .waveType(WaveCount.WaveType.DOUBLE_ZIGZAG).primaryTimeframe(tf).bullish(false)
                .pivotToWave(labels).pivots(List.of(wxStart, wxC, connectorEnd, wyC))
                .currentWaveInProgress(WaveLabel.UNKNOWN)
                .currentPositionDescription("Double Zigzag down: WX=" + fmt(wxStart.getPrice())
                    + "→" + fmt(wxC.getPrice()) + " connector→" + fmt(connectorEnd.getPrice())
                    + " WY→" + fmt(wyC.getPrice()) + " (WY/WX=" + fmt(wyToWx) + ")")
                .fibonacciScore(fibScore)
                .indicatorScore(0).crossTfScore(0).alternationScore(0)
                .waveBRetracePct(connectorRetrace)
                .supportingEvidence(supporting).contradictingEvidence(contradicting)
                .ruleViolations(new ArrayList<>()).build();
    }

    /**
     * Double Zigzag Up: WX (first zigzag up) + connector W (retrace down) + WY (second zigzag up).
     */
    private WaveCount tryFitDoubleZigzagUp(List<EnrichedPivot> pivots, int startIdx, String tf) {
        EnrichedPivot wxStart = pivots.get(startIdx);
        EnrichedPivot wxA = firstHighAfter(pivots, wxStart.getBarIndex());
        if (wxA == null) return null;
        double wxALen = wxA.getPrice() - wxStart.getPrice();
        if (wxALen <= 0) return null;

        EnrichedPivot wxB = firstLowAfter(pivots, wxA.getBarIndex());
        if (wxB == null) return null;
        double wxBRetrace = (wxA.getPrice() - wxB.getPrice()) / wxALen;
        if (wxBRetrace > B_ZIGZAG_MAX) return null;

        EnrichedPivot wxC = firstHighAfter(pivots, wxB.getBarIndex());
        if (wxC == null) return null;
        double wxLength = wxC.getPrice() - wxStart.getPrice();
        if (wxLength <= 0) return null;

        // Connector W: retrace down 25–61.8% of WX length
        EnrichedPivot connectorEnd = firstLowAfter(pivots, wxC.getBarIndex());
        if (connectorEnd == null) return null;
        double connectorRetrace = (wxC.getPrice() - connectorEnd.getPrice()) / wxLength;
        if (connectorRetrace < 0.25 || connectorRetrace > 0.618) return null;

        EnrichedPivot wyA = firstHighAfter(pivots, connectorEnd.getBarIndex());
        if (wyA == null) {
            Map<Integer, WaveLabel> labels = new LinkedHashMap<>();
            labels.put(wxStart.getBarIndex(), WaveLabel.WX);
            labels.put(wxC.getBarIndex(),     WaveLabel.WX);
            labels.put(connectorEnd.getBarIndex(), WaveLabel.WY);
            return WaveCount.builder()
                    .waveType(WaveCount.WaveType.DOUBLE_ZIGZAG).primaryTimeframe(tf).bullish(true)
                    .pivotToWave(labels).pivots(List.of(wxStart, wxC, connectorEnd))
                    .currentWaveInProgress(WaveLabel.WY)
                    .currentPositionDescription("Double Zigzag up — WX complete, connector done, WY forming")
                    .fibonacciScore(8).indicatorScore(0).crossTfScore(0).alternationScore(0)
                    .supportingEvidence(new ArrayList<>()).contradictingEvidence(new ArrayList<>())
                    .ruleViolations(new ArrayList<>()).build();
        }

        EnrichedPivot wyB = firstLowAfter(pivots, wyA.getBarIndex());
        if (wyB == null) return null;
        double wyALen = wyA.getPrice() - connectorEnd.getPrice();
        if (wyALen <= 0) return null;
        double wyBRetrace = (wyA.getPrice() - wyB.getPrice()) / wyALen;
        if (wyBRetrace > B_ZIGZAG_MAX) return null;

        EnrichedPivot wyC = firstHighAfter(pivots, wyB.getBarIndex());
        if (wyC == null) {
            Map<Integer, WaveLabel> labels = new LinkedHashMap<>();
            labels.put(wxStart.getBarIndex(), WaveLabel.WX);
            labels.put(wxC.getBarIndex(),     WaveLabel.WX);
            labels.put(connectorEnd.getBarIndex(), WaveLabel.WY);
            labels.put(wyB.getBarIndex(),     WaveLabel.WC);
            return WaveCount.builder()
                    .waveType(WaveCount.WaveType.DOUBLE_ZIGZAG).primaryTimeframe(tf).bullish(true)
                    .pivotToWave(labels).pivots(List.of(wxStart, wxC, connectorEnd, wyB))
                    .currentWaveInProgress(WaveLabel.WC)
                    .currentPositionDescription("Double Zigzag up — WY C-wave forming")
                    .fibonacciScore(10).indicatorScore(0).crossTfScore(0).alternationScore(0)
                    .supportingEvidence(new ArrayList<>()).contradictingEvidence(new ArrayList<>())
                    .ruleViolations(new ArrayList<>()).build();
        }

        // WY must extend beyond WX end (wyC > wxC)
        if (wyC.getPrice() <= wxC.getPrice()) return null;

        double wyLength = wyC.getPrice() - connectorEnd.getPrice();
        double wyToWx = wyLength / wxLength;

        List<WaveEvidence> supporting = new ArrayList<>();
        List<WaveEvidence> contradicting = new ArrayList<>();
        if (connectorRetrace >= 0.382 && connectorRetrace <= 0.618)
            supporting.add(WaveEvidence.supports("Fibonacci", "Connector retraces " + fmt(connectorRetrace * 100) + "% of WX (ideal 38.2–61.8%)"));
        if (wyToWx >= 0.9 && wyToWx <= 1.1)
            supporting.add(WaveEvidence.supports("Fibonacci", "WY ≈ WX (equality ratio " + fmt(wyToWx) + ")"));
        else
            contradicting.add(WaveEvidence.contradicts("Fibonacci", "WY/WX ratio " + fmt(wyToWx) + " outside ideal 0.9–1.1"));

        int fibScore = 0;
        fibScore += fibProximityScore(connectorRetrace, 0.500, 10);
        fibScore += fibProximityScore(wyToWx, 1.0, 12);

        Map<Integer, WaveLabel> labels = new LinkedHashMap<>();
        labels.put(wxStart.getBarIndex(),       WaveLabel.WX);
        labels.put(wxC.getBarIndex(),           WaveLabel.WX);
        labels.put(connectorEnd.getBarIndex(),  WaveLabel.WY);
        labels.put(wyC.getBarIndex(),           WaveLabel.WY);

        return WaveCount.builder()
                .waveType(WaveCount.WaveType.DOUBLE_ZIGZAG).primaryTimeframe(tf).bullish(true)
                .pivotToWave(labels).pivots(List.of(wxStart, wxC, connectorEnd, wyC))
                .currentWaveInProgress(WaveLabel.UNKNOWN)
                .currentPositionDescription("Double Zigzag up: WX=" + fmt(wxStart.getPrice())
                    + "→" + fmt(wxC.getPrice()) + " connector→" + fmt(connectorEnd.getPrice())
                    + " WY→" + fmt(wyC.getPrice()) + " (WY/WX=" + fmt(wyToWx) + ")")
                .fibonacciScore(fibScore)
                .indicatorScore(0).crossTfScore(0).alternationScore(0)
                .waveBRetracePct(connectorRetrace)
                .supportingEvidence(supporting).contradictingEvidence(contradicting)
                .ruleViolations(new ArrayList<>()).build();
    }

    private WaveCount tryFitTriangle(List<EnrichedPivot> pivots, String tf) {
        if (pivots.size() < 6) return null;
        List<EnrichedPivot> recent = pivots.subList(Math.max(0, pivots.size() - 10), pivots.size());
        List<EnrichedPivot> rHighs = recent.stream().filter(EnrichedPivot::isHigh).toList();
        List<EnrichedPivot> rLows  = recent.stream().filter(EnrichedPivot::isLow).toList();
        if (rHighs.size() < 2 || rLows.size() < 2) return null;

        boolean descHighs = rHighs.get(rHighs.size()-1).getPrice() < rHighs.get(0).getPrice();
        boolean ascLows   = rLows.get(rLows.size()-1).getPrice()   > rLows.get(0).getPrice();
        double highRange  = rHighs.get(0).getPrice() > 0
            ? (rHighs.get(0).getPrice() - rHighs.get(rHighs.size()-1).getPrice()) / rHighs.get(0).getPrice() : 1.0;
        double lowRange   = rLows.get(0).getPrice() > 0
            ? (rLows.get(rLows.size()-1).getPrice() - rLows.get(0).getPrice()) / rLows.get(0).getPrice() : 1.0;
        boolean flatHighs = highRange < 0.015;
        boolean flatLows  = lowRange  < 0.015;

        String description;
        if      (descHighs && ascLows)  description = "Symmetrical Triangle: descending highs + ascending lows";
        else if (flatHighs && ascLows)  description = "Ascending Triangle: flat resistance ~" + fmt(rHighs.get(0).getPrice()) + " + rising support";
        else if (descHighs && flatLows) description = "Descending Triangle: falling resistance + flat support ~" + fmt(rLows.get(0).getPrice());
        else return null;

        List<EnrichedPivot> last5 = recent.subList(Math.max(0, recent.size() - 5), recent.size());
        if (last5.size() < 5) return buildTriangleBuilding(recent, tf, description);

        Map<Integer, WaveLabel> labels = new LinkedHashMap<>();
        WaveLabel[] triLabels = {WaveLabel.WA, WaveLabel.WB, WaveLabel.WC, WaveLabel.WD, WaveLabel.WE};
        for (int i = 0; i < last5.size(); i++) labels.put(last5.get(i).getBarIndex(), triLabels[i]);

        boolean bbSqueeze      = recent.stream().anyMatch(EnrichedPivot::isBollingerSqueeze);
        long macdNearZeroCount = recent.stream().filter(EnrichedPivot::isMacdNearZero).count();
        boolean macdW4Sig      = macdNearZeroCount >= recent.size() / 2;

        List<WaveEvidence> sup = new ArrayList<>();
        if (bbSqueeze)  sup.add(WaveEvidence.supports("Bollinger", "Squeeze inside triangle — breakout imminent"));
        if (macdW4Sig)  sup.add(WaveEvidence.supports("MACD", "MACD near zero — W4 sub-wave oscillation signature"));

        return WaveCount.builder()
                .waveType(WaveCount.WaveType.TRIANGLE).primaryTimeframe(tf)
                .bullish(true)
                .pivotToWave(labels).pivots(last5)
                .currentWaveInProgress(WaveLabel.WE)
                .currentPositionDescription(description)
                .fibonacciScore(20)
                .indicatorScore((bbSqueeze ? 15 : 5) + (macdW4Sig ? 10 : 0))
                .crossTfScore(0).alternationScore(0)
                .supportingEvidence(sup).contradictingEvidence(new ArrayList<>()).ruleViolations(new ArrayList<>())
                .build();
    }

    private WaveCount buildTriangleBuilding(List<EnrichedPivot> recent, String tf, String desc) {
        return WaveCount.builder()
                .waveType(WaveCount.WaveType.TRIANGLE)
                .primaryTimeframe(tf)
                .bullish(true)
                .pivotToWave(new LinkedHashMap<>())
                .pivots(recent)
                .currentWaveInProgress(WaveLabel.WC)
                .currentPositionDescription("Triangle forming — " + desc)
                .fibonacciScore(10).indicatorScore(0).crossTfScore(0).alternationScore(0)
                .supportingEvidence(new ArrayList<>()).contradictingEvidence(new ArrayList<>()).ruleViolations(new ArrayList<>())
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CROSS-TF VALIDATION
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * For each WaveCount, find its parent/child TF pivots and check if the internal
     * structure matches expectations: impulse wave should show 5 child-TF swings,
     * corrective wave should show 3 child-TF swings.
     */
    private void crossTfValidate(List<WaveCount> counts,
                                  Map<String, List<EnrichedPivot>> pivotsByTf,
                                  List<String> tfOrder) {
        for (WaveCount wc : counts) {
            int tfIndex = tfOrder.indexOf(wc.getPrimaryTimeframe());
            if (tfIndex < 0) continue;

            if (tfIndex + 1 >= tfOrder.size()) {
                // Last TF in chain — no child. Score by parent direction alignment.
                if (tfIndex > 0) {
                    String parentTf = tfOrder.get(tfIndex - 1);
                    // Find the best-scoring parent count IN THE SAME DIRECTION as this child count
                    counts.stream()
                        .filter(other -> parentTf.equals(other.getPrimaryTimeframe())
                                      && other.isBullish() == wc.isBullish())
                        .max(java.util.Comparator.comparingInt(WaveCount::totalScore))
                        .ifPresent(sameDir -> {
                            wc.setCrossTfScore(wc.getCrossTfScore() + 10);
                            wc.getSupportingEvidence().add(WaveEvidence.supports("Cross-TF",
                                "Parent " + parentTf + " has matching "
                                + (wc.isBullish() ? "bullish" : "bearish")
                                + " wave count (score=" + sameDir.totalScore() + ")"));
                        });
                    // Add contradicting evidence only if the strongest opposite parent count
                    // significantly outscores any same-direction parent count
                    OptionalInt sameDirBest = counts.stream()
                        .filter(other -> parentTf.equals(other.getPrimaryTimeframe())
                                      && other.isBullish() == wc.isBullish())
                        .mapToInt(WaveCount::totalScore).max();
                    OptionalInt oppDirBest = counts.stream()
                        .filter(other -> parentTf.equals(other.getPrimaryTimeframe())
                                      && other.isBullish() != wc.isBullish())
                        .mapToInt(WaveCount::totalScore).max();
                    if (oppDirBest.isPresent() && oppDirBest.getAsInt() > 30
                            && (!sameDirBest.isPresent() || oppDirBest.getAsInt() > sameDirBest.getAsInt() + 10)) {
                        wc.getContradictingEvidence().add(WaveEvidence.contradicts("Cross-TF",
                            "Parent " + parentTf + " dominant direction opposes this count",
                            0.6));
                    }
                }
                continue;
            }

            String childTf = tfOrder.get(tfIndex + 1);
            List<EnrichedPivot> childPivots = pivotsByTf.get(childTf);
            if (childPivots == null || childPivots.isEmpty()) continue;

            // Check W3 internal structure (should be 5 child swings = impulse)
            if (wc.getPivots().size() >= 4) {
                EnrichedPivot w2 = wc.getPivots().get(2);  // W2 end
                EnrichedPivot w3 = wc.getPivots().get(3);  // W3 end
                int childSwings = countSwingsBetween(childPivots, w2.getTimestamp(), w3.getTimestamp());
                // Impulse W3 internal structure: odd number of pivots >= 5 (5,7,9,... sub-swings)
                if (childSwings >= 5 && childSwings % 2 == 1) {
                    wc.setCrossTfScore(wc.getCrossTfScore() + 10);
                    wc.getSupportingEvidence().add(WaveEvidence.supports("Cross-TF",
                            "W3 internal structure = " + childSwings + " swings (impulse-like) on " + childTf));
                } else if (childSwings == 3) {
                    wc.getContradictingEvidence().add(WaveEvidence.contradicts("Cross-TF",
                            "W3 shows only 3 child-TF swings (corrective internal = lower confidence)"));
                } else if (childSwings > 0) {
                    // Even number or >= 5 even: partial credit for complex structure
                    if (childSwings >= 4) {
                        wc.setCrossTfScore(wc.getCrossTfScore() + 5);
                        wc.getSupportingEvidence().add(WaveEvidence.supports("Cross-TF",
                                "W3 internal structure = " + childSwings + " swings on " + childTf + " (partial confirmation)"));
                    }
                }
            }

            // Check W1 internal structure (should be 5+ impulse swings on child TF)
            if (wc.getPivots().size() >= 2) {
                EnrichedPivot w1s = wc.getPivots().get(0);
                EnrichedPivot w1e = wc.getPivots().get(1);
                int w1Swings = countSwingsBetween(childPivots, w1s.getTimestamp(), w1e.getTimestamp());
                if (w1Swings >= 5 && w1Swings % 2 == 1) {
                    wc.setCrossTfScore(wc.getCrossTfScore() + 8);
                    wc.getSupportingEvidence().add(WaveEvidence.supports("Cross-TF",
                        "W1 internal structure = " + w1Swings + " swings (impulse-like) on " + childTf));
                } else if (w1Swings == 3) {
                    wc.getContradictingEvidence().add(WaveEvidence.contradicts("Cross-TF",
                        "W1 shows only 3 child-TF swings on " + childTf + " — W1 may be corrective, not impulsive"));
                }
            }

            // Check W2 internal structure (should be 3 corrective swings on child TF)
            if (wc.getPivots().size() >= 3) {
                EnrichedPivot w1e = wc.getPivots().get(1);
                EnrichedPivot w2e = wc.getPivots().get(2);
                int w2Swings = countSwingsBetween(childPivots, w1e.getTimestamp(), w2e.getTimestamp());
                if (w2Swings == 3) {
                    wc.setCrossTfScore(wc.getCrossTfScore() + 8);
                    wc.getSupportingEvidence().add(WaveEvidence.supports("Cross-TF",
                        "W2 internal structure = 3 swings (corrective) on " + childTf));
                } else if (w2Swings >= 5 && w2Swings % 2 == 1) {
                    wc.getContradictingEvidence().add(WaveEvidence.contradicts("Cross-TF",
                        "W2 shows " + w2Swings + " child-TF swings on " + childTf + " — W2 may be impulsive (not corrective)"));
                }
            }

            // Check W4 internal structure (should be 3 corrective swings on child TF)
            if (wc.getPivots().size() >= 5) {
                EnrichedPivot w3ep = wc.getPivots().get(3);
                EnrichedPivot w4ep = wc.getPivots().get(4);
                int w4Swings = countSwingsBetween(childPivots, w3ep.getTimestamp(), w4ep.getTimestamp());
                if (w4Swings == 3) {
                    wc.setCrossTfScore(wc.getCrossTfScore() + 8);
                    wc.getSupportingEvidence().add(WaveEvidence.supports("Cross-TF",
                        "W4 internal structure = 3 swings (corrective) on " + childTf));
                } else if (w4Swings >= 5 && w4Swings % 2 == 1) {
                    wc.getContradictingEvidence().add(WaveEvidence.contradicts("Cross-TF",
                        "W4 shows " + w4Swings + " child-TF swings on " + childTf + " — W4 may be impulsive (not corrective)"));
                }
            }

            // Check W5 internal structure (should be 5+ impulse swings on child TF)
            if (wc.getPivots().size() >= 6) {
                EnrichedPivot w4ep = wc.getPivots().get(4);
                EnrichedPivot w5ep = wc.getPivots().get(5);
                int w5Swings = countSwingsBetween(childPivots, w4ep.getTimestamp(), w5ep.getTimestamp());
                if (w5Swings >= 5 && w5Swings % 2 == 1) {
                    wc.setCrossTfScore(wc.getCrossTfScore() + 6);
                    wc.getSupportingEvidence().add(WaveEvidence.supports("Cross-TF",
                        "W5 internal structure = " + w5Swings + " swings (impulse-like) on " + childTf));
                } else if (w5Swings == 3) {
                    wc.getContradictingEvidence().add(WaveEvidence.contradicts("Cross-TF",
                        "W5 shows only 3 child-TF swings on " + childTf + " — W5 may be corrective (truncation/failure risk)"));
                }
            }
        }
    }

    private int countSwingsBetween(List<EnrichedPivot> pivots, java.time.Instant fromTime, java.time.Instant toTime) {
        return (int) pivots.stream()
                .filter(p -> p.getTimestamp() != null
                        && !p.getTimestamp().isBefore(fromTime)
                        && !p.getTimestamp().isAfter(toTime))
                .count();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SCORING HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private int scoreFibImpulseUp(double w1, double w2r, double w3, double w4r, double w5) {
        int score = 0;
        score += fibProximityScore(w2r, W2_IDEAL_RETRACE,   8);
        score += fibProximityScore(w3 / w1, W3_IDEAL_EXTENSION, 16);
        score += fibProximityScore(w4r, W4_IDEAL_RETRACE,   8);
        // W5 multi-ideal: primary=61.8%, secondary=100%, extended=161.8%, also check vs net W1-W3 distance
        double w1NetToW3 = w1 * (1.0 - w2r) + w3; // net price distance from W1 start to W3 end
        int w5Score = Math.max(
            Math.max(fibProximityScore(w5 / w1, 0.618, 8),   // primary: W5 = 61.8% of W1
                     fibProximityScore(w5 / w1, 1.0,   6)),  // secondary: W5 = 100% of W1
            Math.max(fibProximityScore(w5 / w1, 1.618, 4),   // extended: W5 = 161.8% of W1
                     w1NetToW3 > 0 ? fibProximityScore(w5 / w1NetToW3, 0.618, 6) : 0)); // W5 = 61.8% of W1start→W3end
        score += w5Score;
        return score;  // max 42
    }

    private int scoreFibImpulseDown(double w1, double w2r, double w3, double w4r, double w5) {
        return scoreFibImpulseUp(w1, w2r, w3, w4r, w5);
    }

    private int scoreFibCorrection(double bRetrace, double cToA, WaveCount.WaveType type) {
        int score = 0;
        if (type == WaveCount.WaveType.ZIGZAG) {
            score += fibProximityScore(bRetrace, 0.618, 12);
            score += fibProximityScore(cToA, 1.0, 12);
        } else {
            score += fibProximityScore(bRetrace, 1.0, 12);
            score += fibProximityScore(cToA, 1.0, 12);
        }
        return score;
    }

    private int fibProximityScore(double actual, double ideal, int maxPoints) {
        double deviation = Math.abs(actual - ideal) / ideal;
        if (deviation <= 0.05) return maxPoints;
        if (deviation <= 0.10) return (int) (maxPoints * 0.7);
        if (deviation <= FIB_TOLERANCE) return (int) (maxPoints * 0.4);
        return 0;
    }

    private int scoreIndicatorsImpulseUp(EnrichedPivot w1e, EnrichedPivot w2e, EnrichedPivot w3e,
                                          EnrichedPivot w4e, EnrichedPivot w5e) {
        int score = 0;
        // W3 EWO peak — magnitude-weighted (0–10 points)
        if (w3e.getEwo() > w1e.getEwo() && w3e.getEwo() > w5e.getEwo() && w3e.getEwo() != 0) {
            double ewoLead = (w3e.getEwo() - Math.max(w1e.getEwo(), w5e.getEwo())) / Math.abs(w3e.getEwo());
            score += (int) Math.min(Math.round(ewoLead * 10), 10);
        }
        // W5 MACD divergence — magnitude-weighted (0–10 points based on how strong the divergence is)
        if (w3e.getMacdHistogram() != 0 && w5e.getMacdHistogram() < w3e.getMacdHistogram()) {
            double macdDiv = (w3e.getMacdHistogram() - w5e.getMacdHistogram()) / Math.abs(w3e.getMacdHistogram());
            score += (int) Math.min(Math.round(macdDiv * 10), 10);
        }
        // W3 Bollinger walk — momentum confirmation (adds probability)
        if (w3e.isBollingerWalk()) score += 7;
        // W4 stochastic oversold — correction exhaustion (adds probability)
        if (w4e.getStochK() < 30) score += 7;
        // W4 MACD amplitude contracting vs W3 — W4 signature (adds probability)
        boolean w4MacdContracting = w4e.getMacdHistogramAmplitude() < w3e.getMacdHistogramAmplitude() * 0.5;
        if (w4MacdContracting) score += 8;
        // W4 MACD near zero — consolidation oscillation signature (adds probability)
        if (w4e.isMacdNearZero()) score += 7;
        // W3 volume expansion (relative volume) — confirms impulse momentum
        if (w3e.getRelativeVolume() > 0 && w1e.getRelativeVolume() > 0
                && w3e.getRelativeVolume() > w1e.getRelativeVolume()) {
            score += 4;
        }
        // W5 volume diminishing vs W3 — exhaustion / divergence confirmation
        if (w3e.getRelativeVolume() > 0 && w5e.getRelativeVolume() > 0
                && w5e.getRelativeVolume() < w3e.getRelativeVolume()) {
            score += 4;
        }
        // RSI divergence at W5: lower RSI at W5 than at W3 = bearish divergence confirming exhaustion
        if (w3e.getRsi() > 0 && w5e.getRsi() > 0 && w5e.getRsi() < w3e.getRsi()) {
            score += 5;
        }
        return Math.min(score, 45);
    }

    private int scoreIndicatorsImpulseDown(EnrichedPivot w1e, EnrichedPivot w2e, EnrichedPivot w3e,
                                            EnrichedPivot w4e, EnrichedPivot w5e) {
        int score = 0;
        if (w3e.getEwo() < w1e.getEwo() && w3e.getEwo() < w5e.getEwo() && w3e.getEwo() != 0) {
            double ewoLead = (Math.min(w1e.getEwo(), w5e.getEwo()) - w3e.getEwo()) / Math.abs(w3e.getEwo());
            score += (int) Math.min(Math.round(ewoLead * 10), 10);
        }
        if (w3e.getMacdHistogram() != 0 && w5e.getMacdHistogram() > w3e.getMacdHistogram()) {
            double macdDiv = (w5e.getMacdHistogram() - w3e.getMacdHistogram()) / Math.abs(w3e.getMacdHistogram());
            score += (int) Math.min(Math.round(macdDiv * 10), 10);
        }
        // W3 Bollinger walk (lower band for bearish) — momentum confirmation
        if (w3e.isBollingerWalk()) score += 7;
        if (w4e.getStochK() > 70) score += 7;
        boolean w4MacdContracting = w4e.getMacdHistogramAmplitude() < w3e.getMacdHistogramAmplitude() * 0.5;
        if (w4MacdContracting) score += 8;
        if (w4e.isMacdNearZero()) score += 7;
        if (w3e.getRelativeVolume() > 0 && w1e.getRelativeVolume() > 0
                && w3e.getRelativeVolume() > w1e.getRelativeVolume()) {
            score += 4;
        }
        if (w3e.getRelativeVolume() > 0 && w5e.getRelativeVolume() > 0
                && w5e.getRelativeVolume() < w3e.getRelativeVolume()) {
            score += 4;
        }
        // RSI divergence at W5: higher RSI at W5 than at W3 = bullish divergence confirming bearish exhaustion
        if (w3e.getRsi() > 0 && w5e.getRsi() > 0 && w5e.getRsi() > w3e.getRsi()) {
            score += 5;
        }
        return Math.min(score, 45);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PARTIAL / BUILDING WAVE COUNT FACTORIES
    // ══════════════════════════════════════════════════════════════════════════

    private WaveCount buildPartialImpulse(EnrichedPivot w1s, EnrichedPivot w1e,
                                           EnrichedPivot w2e, EnrichedPivot w3e,
                                           EnrichedPivot w4e, EnrichedPivot w5e,
                                           String tf, String description) {
        Map<Integer, WaveLabel> labels = new LinkedHashMap<>();
        List<EnrichedPivot> pivots = new ArrayList<>();
        if (w1s != null) { labels.put(w1s.getBarIndex(), WaveLabel.W1); pivots.add(w1s); }
        if (w1e != null) { labels.put(w1e.getBarIndex(), WaveLabel.W1); pivots.add(w1e); }
        if (w2e != null) { labels.put(w2e.getBarIndex(), WaveLabel.W2); pivots.add(w2e); }
        if (w3e != null) { labels.put(w3e.getBarIndex(), WaveLabel.W3); pivots.add(w3e); }
        if (w4e != null) { labels.put(w4e.getBarIndex(), WaveLabel.W4); pivots.add(w4e); }

        WaveLabel inProgress = w4e != null ? WaveLabel.W5
                : w3e != null ? WaveLabel.W4
                : w2e != null ? WaveLabel.W3
                : w1e != null ? WaveLabel.W2 : WaveLabel.W1;

        return WaveCount.builder()
                .waveType(WaveCount.WaveType.IMPULSE)
                .primaryTimeframe(tf)
                .bullish(!description.startsWith("bearish"))
                .pivotToWave(labels)
                .pivots(pivots)
                .currentWaveInProgress(inProgress)
                .currentPositionDescription((description.startsWith("bearish")
                        ? "Partial impulse down — "
                        : "Partial impulse up — ") + description)
                .fibonacciScore(5)
                .indicatorScore(0)
                .crossTfScore(0)
                .alternationScore(0)
                .supportingEvidence(new ArrayList<>())
                .contradictingEvidence(new ArrayList<>())
                .ruleViolations(new ArrayList<>())
                .build();
    }

    private WaveCount buildPartialImpulseDown(EnrichedPivot w1s, EnrichedPivot w1e,
                                               EnrichedPivot w2e, EnrichedPivot w3e,
                                               EnrichedPivot w4e, EnrichedPivot w5e,
                                               String tf, String desc) {
        return buildPartialImpulse(w1s, w1e, w2e, w3e, w4e, w5e, tf, "bearish — " + desc);
    }

    private WaveCount buildImpulseWithViolations(EnrichedPivot w1s, EnrichedPivot w1e,
                                                  EnrichedPivot w2e, EnrichedPivot w3e,
                                                  EnrichedPivot w4e, EnrichedPivot w5e,
                                                  String tf, List<String> violations) {
        WaveCount wc = buildPartialImpulse(w1s, w1e, w2e, w3e, w4e, w5e, tf, "with violations");
        wc.setRuleViolations(violations);
        return wc;
    }

    private WaveCount buildImpulseDownWithViolations(EnrichedPivot w1s, EnrichedPivot w1e,
                                                      EnrichedPivot w2e, EnrichedPivot w3e,
                                                      EnrichedPivot w4e, EnrichedPivot w5e,
                                                      String tf, List<String> violations) {
        return buildImpulseWithViolations(w1s, w1e, w2e, w3e, w4e, w5e, tf, violations);
    }

    private WaveCount buildZigzagBuilding(EnrichedPivot wAs, EnrichedPivot wAe,
                                           EnrichedPivot wBe, EnrichedPivot wCe,
                                           String tf, String desc) {
        return buildCorrectionBuilding(wAs, wAe, wBe, wCe, WaveCount.WaveType.ZIGZAG, tf, desc);
    }

    private WaveCount buildCorrectionBuilding(EnrichedPivot wAs, EnrichedPivot wAe,
                                               EnrichedPivot wBe, EnrichedPivot wCe,
                                               WaveCount.WaveType type, String tf, String desc) {
        Map<Integer, WaveLabel> labels = new LinkedHashMap<>();
        List<EnrichedPivot> pivots = new ArrayList<>();
        if (wAs != null) { labels.put(wAs.getBarIndex(), WaveLabel.WA); pivots.add(wAs); }
        if (wAe != null) { labels.put(wAe.getBarIndex(), WaveLabel.WA); pivots.add(wAe); }
        if (wBe != null) { labels.put(wBe.getBarIndex(), WaveLabel.WB); pivots.add(wBe); }
        if (wCe != null) { labels.put(wCe.getBarIndex(), WaveLabel.WC); pivots.add(wCe); }

        WaveLabel inProgress = wBe != null ? WaveLabel.WC : wAe != null ? WaveLabel.WB : WaveLabel.WA;

        return WaveCount.builder()
                .waveType(type)
                .primaryTimeframe(tf)
                .bullish(wAe != null && wAs != null && wAe.getPrice() > wAs.getPrice())
                .pivotToWave(labels)
                .pivots(pivots)
                .currentWaveInProgress(inProgress)
                .currentPositionDescription("Partial " + type.name() + " — " + desc)
                .fibonacciScore(5)
                .indicatorScore(0)
                .crossTfScore(0)
                .alternationScore(0)
                .supportingEvidence(new ArrayList<>())
                .contradictingEvidence(new ArrayList<>())
                .ruleViolations(new ArrayList<>())
                .build();
    }


    // ══════════════════════════════════════════════════════════════════════════
    // EVIDENCE GATHERING
    // ══════════════════════════════════════════════════════════════════════════

    private void gatherImpulseEvidence(
            EnrichedPivot w1s, EnrichedPivot w1e, EnrichedPivot w2e,
            EnrichedPivot w3e, EnrichedPivot w4e, EnrichedPivot w5e,
            double w2r, double w3ext, double w4r,
            List<WaveEvidence> sup, List<WaveEvidence> contra) {

        if (w3e.getEwo() > w1e.getEwo()) sup.add(WaveEvidence.supports("EWO", "EWO peak at W3 — confirms strongest wave", w3e.getEwo()));
        else contra.add(WaveEvidence.contradicts("EWO", "EWO did not peak at W3", w3e.getEwo()));

        if (w5e.getMacdHistogram() < w3e.getMacdHistogram())
            sup.add(WaveEvidence.supports("MACD", "MACD histogram divergence at W5 vs W3 — exhaustion signal"));
        else
            contra.add(WaveEvidence.contradicts("MACD", "No MACD divergence at W5 — impulse may extend"));

        if (w3e.isBollingerWalk())
            sup.add(WaveEvidence.supports("Bollinger", "W3 rode the upper Bollinger Band — momentum confirmation"));

        if (w4e.getStochK() < 40)
            sup.add(WaveEvidence.supports("Stochastic", "W4 stochastic oversold at " + fmt(w4e.getStochK())));

        if (w3ext >= 1.5)
            sup.add(WaveEvidence.supports("Fibonacci", "W3 extension = " + fmt(w3ext * 100) + "% of W1 (>150%)"));
        else if (w3ext < 1.0)
            contra.add(WaveEvidence.contradicts("Fibonacci", "W3 shorter than W1 (extension=" + fmt(w3ext * 100) + "%)"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PIVOT NAVIGATION HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private EnrichedPivot firstHighAfter(List<EnrichedPivot> pivots, int afterBarIndex) {
        return pivots.stream().filter(p -> p.isHigh() && p.getBarIndex() > afterBarIndex).findFirst().orElse(null);
    }

    private EnrichedPivot firstLowAfter(List<EnrichedPivot> pivots, int afterBarIndex) {
        return pivots.stream().filter(p -> p.isLow() && p.getBarIndex() > afterBarIndex).findFirst().orElse(null);
    }

    private String fmt(double v) { return String.format("%.2f", v); }

    /**
     * Structural-only impulse scan: validates hard geometric rules only (no Fibonacci ratios).
     * Catches extended Wave 3 (> 2.0×W1), running corrections, and unusual extensions
     * that fail the ±15% Fibonacci tolerance but are geometrically valid impulses.
     */
    private List<WaveCount> scanImpulseStructuralOnly(List<EnrichedPivot> pivots, String tf) {
        List<WaveCount> results = new ArrayList<>();
        for (int start = 0; start < pivots.size() - 5; start++) {
            EnrichedPivot p0 = pivots.get(start);
            if (p0.isLow()) {
                WaveCount up = tryFitImpulseUpStructuralOnly(pivots, start, tf);
                if (up != null) results.add(up);
            }
            if (p0.isHigh()) {
                WaveCount down = tryFitImpulseDownStructuralOnly(pivots, start, tf);
                if (down != null) results.add(down);
            }
        }
        return results;
    }

    private WaveCount tryFitImpulseUpStructuralOnly(List<EnrichedPivot> pivots, int startIdx, String tf) {
        List<EnrichedPivot> remaining = pivots.subList(startIdx, pivots.size());
        List<EnrichedPivot> lows  = remaining.stream().filter(EnrichedPivot::isLow).toList();
        List<EnrichedPivot> highs = remaining.stream().filter(EnrichedPivot::isHigh).toList();
        if (highs.size() < 3 || lows.size() < 2) return null;

        EnrichedPivot w1Start = remaining.get(0);
        EnrichedPivot w1End   = highs.get(0);
        EnrichedPivot w2End   = lows.get(0);
        EnrichedPivot w3End   = highs.get(1);
        EnrichedPivot w4End   = lows.get(1);
        EnrichedPivot w5End   = highs.size() >= 3 ? highs.get(2) : null;
        if (w5End == null) return null;

        // Hard structural rules only
        double w1Len = w1End.getPrice() - w1Start.getPrice();
        if (w1Len <= 0) return null;
        if (w2End.getPrice() <= w1Start.getPrice()) return null;         // W2 > 100% retrace
        if (w3End.getPrice() <= w1End.getPrice()) return null;           // W3 must exceed W1 end
        if (w4End.getPrice() <= w1End.getPrice()) return null;           // W4 must stay above W1 end
        double w3Len = w3End.getPrice() - w2End.getPrice();
        double w5Len = w5End.getPrice() - w4End.getPrice();
        if (w3Len < w1Len && w3Len < w5Len) return null;                // W3 not shortest

        Map<Integer, WaveLabel> labels = new LinkedHashMap<>();
        labels.put(w1Start.getBarIndex(), WaveLabel.W1);
        labels.put(w1End.getBarIndex(), WaveLabel.W1);
        labels.put(w2End.getBarIndex(), WaveLabel.W2);
        labels.put(w3End.getBarIndex(), WaveLabel.W3);
        labels.put(w4End.getBarIndex(), WaveLabel.W4);
        labels.put(w5End.getBarIndex(), WaveLabel.W5);

        return WaveCount.builder()
                .waveType(WaveCount.WaveType.IMPULSE)
                .primaryTimeframe(tf)
                .bullish(true)
                .pivotToWave(labels)
                .pivots(java.util.Arrays.asList(w1Start, w1End, w2End, w3End, w4End, w5End))
                .currentWaveInProgress(WaveLabel.UNKNOWN)
                .currentPositionDescription("Structural-only impulse up (extended W3 " + fmt(w3Len / w1Len) + "×W1)")
                .fibonacciScore(0)
                .indicatorScore(scoreIndicatorsImpulseUp(w1End, w2End, w3End, w4End, w5End))
                .crossTfScore(0)
                .alternationScore(0)
                .structuralOnly(true)
                .supportingEvidence(new ArrayList<>())
                .contradictingEvidence(new ArrayList<>())
                .ruleViolations(new ArrayList<>())
                .build();
    }

    private WaveCount tryFitImpulseDownStructuralOnly(List<EnrichedPivot> pivots, int startIdx, String tf) {
        List<EnrichedPivot> remaining = pivots.subList(startIdx, pivots.size());
        List<EnrichedPivot> highs = remaining.stream().filter(EnrichedPivot::isHigh).toList();
        List<EnrichedPivot> lows  = remaining.stream().filter(EnrichedPivot::isLow).toList();
        if (lows.size() < 3 || highs.size() < 2) return null;

        EnrichedPivot w1Start = remaining.get(0);
        EnrichedPivot w1End   = lows.get(0);
        EnrichedPivot w2End   = highs.get(0);
        EnrichedPivot w3End   = lows.get(1);
        EnrichedPivot w4End   = highs.get(1);
        EnrichedPivot w5End   = lows.size() >= 3 ? lows.get(2) : null;
        if (w5End == null) return null;

        double w1Len = w1Start.getPrice() - w1End.getPrice();
        if (w1Len <= 0) return null;
        if (w2End.getPrice() >= w1Start.getPrice()) return null;
        if (w3End.getPrice() >= w1End.getPrice()) return null;
        if (w4End.getPrice() >= w1End.getPrice()) return null;
        double w3Len = w2End.getPrice() - w3End.getPrice();
        double w5Len = w4End.getPrice() - w5End.getPrice();
        if (w3Len < w1Len && w3Len < w5Len) return null;

        Map<Integer, WaveLabel> labels = new LinkedHashMap<>();
        labels.put(w1Start.getBarIndex(), WaveLabel.W1);
        labels.put(w1End.getBarIndex(), WaveLabel.W1);
        labels.put(w2End.getBarIndex(), WaveLabel.W2);
        labels.put(w3End.getBarIndex(), WaveLabel.W3);
        labels.put(w4End.getBarIndex(), WaveLabel.W4);
        labels.put(w5End.getBarIndex(), WaveLabel.W5);

        return WaveCount.builder()
                .waveType(WaveCount.WaveType.IMPULSE)
                .primaryTimeframe(tf)
                .bullish(false)
                .pivotToWave(labels)
                .pivots(java.util.Arrays.asList(w1Start, w1End, w2End, w3End, w4End, w5End))
                .currentWaveInProgress(WaveLabel.UNKNOWN)
                .currentPositionDescription("Structural-only impulse down (extended W3 " + fmt(w3Len / w1Len) + "×W1)")
                .fibonacciScore(0)
                .indicatorScore(scoreIndicatorsImpulseDown(w1End, w2End, w3End, w4End, w5End))
                .crossTfScore(0)
                .alternationScore(0)
                .structuralOnly(true)
                .supportingEvidence(new ArrayList<>())
                .contradictingEvidence(new ArrayList<>())
                .ruleViolations(new ArrayList<>())
                .build();
    }

    /**
     * Fibonacci scoring for diagonal waves.
     * Ideal diagonal ratios: W2/W4 retrace ~0.618–0.786, W3/W1 ratio ~0.618–0.786 (contracting).
     * Max 38 points (lower than impulse by design — diagonals are lower confidence).
     */
    private int scoreFibDiagonal(double w1, double w2r, double w3, double w4r, double w5, boolean converging) {
        int score = 0;
        // W2 retrace: ideal 66%–78.6% for diagonal (deeper than standard impulse)
        score += fibProximityScore(w2r, 0.786, 8);
        // W3/W1 ratio: contracting diagonal ideal ~0.618–0.786
        score += fibProximityScore(w3 / w1, 0.618, 8);
        // W4 retrace: same as W2 for diagonal
        score += fibProximityScore(w4r, 0.786, 8);
        // W5/W3 ratio: contracting
        score += fibProximityScore(w5 / w3, 0.618, 6);
        // Trendline convergence bonus
        if (converging) score += 8;
        return score; // max 38
    }

    /**
     * Returns true if the W1-W3 trendline and W2-W4 trendline converge (wedge shape).
     * For bullish: slope of highs (W1end→W3end) should be greater than slope of lows (W2end→W4end).
     * For bearish: slope of lows (W1end→W3end) should be less than slope of highs (W2end→W4end).
     */
    private boolean trendlinesConverge(EnrichedPivot w1e, EnrichedPivot w3e,
                                        EnrichedPivot w2e, EnrichedPivot w4e, boolean bullish) {
        if (w3e.getBarIndex() == w1e.getBarIndex() || w4e.getBarIndex() == w2e.getBarIndex()) return false;
        double slopeUpper = (double)(w3e.getPrice() - w1e.getPrice()) / (w3e.getBarIndex() - w1e.getBarIndex());
        double slopeLower = (double)(w4e.getPrice() - w2e.getPrice()) / (w4e.getBarIndex() - w2e.getBarIndex());
        if (bullish) {
            // Both slopes should be positive and upper slope > lower slope (converging upward wedge)
            return slopeUpper > 0 && slopeLower > 0 && slopeUpper > slopeLower;
        } else {
            // Both slopes should be negative and upper slope > lower slope (converging downward wedge)
            return slopeUpper < 0 && slopeLower < 0 && slopeUpper > slopeLower;
        }
    }

    /**
     * Classifies a diagonal as LEADING or ENDING based on its position in the pivot list.
     * If the diagonal starts in the last 60% of the pivot list, it's likely an ENDING diagonal (W5/WC position).
     * Otherwise it's a LEADING diagonal (W1/WA position).
     */
    private WaveCount.WaveType classifyDiagonal(List<EnrichedPivot> allPivots, int startIdx, boolean bullish) {
        double positionRatio = allPivots.isEmpty() ? 0.5 : (double) startIdx / allPivots.size();
        return positionRatio >= 0.60 ? WaveCount.WaveType.ENDING_DIAGONAL : WaveCount.WaveType.LEADING_DIAGONAL;
    }
}
