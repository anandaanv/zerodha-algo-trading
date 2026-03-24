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
    private static final double W5_IDEAL_EXTENSION = 1.0;   // W5 = W1 in length

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
            }

            // Impulse DOWN: starts from a HIGH, ends on a LOW
            if (p0.isHigh()) {
                WaveCount down = tryFitImpulseDown(pivots, start, tf);
                if (down != null) results.add(down);
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
            if (tfIndex < 0 || tfIndex + 1 >= tfOrder.size()) continue;

            String childTf = tfOrder.get(tfIndex + 1);
            List<EnrichedPivot> childPivots = pivotsByTf.get(childTf);
            if (childPivots == null || childPivots.isEmpty()) continue;

            // Check W3 internal structure (should be 5 child swings = impulse)
            if (wc.getPivots().size() >= 4) {
                EnrichedPivot w2 = wc.getPivots().get(2);  // W2 end
                EnrichedPivot w3 = wc.getPivots().get(3);  // W3 end
                int childSwings = countSwingsBetween(childPivots, w2.getTimestamp(), w3.getTimestamp());
                if (childSwings == 5) {
                    wc.setCrossTfScore(wc.getCrossTfScore() + 10);
                    wc.getSupportingEvidence().add(WaveEvidence.supports("Cross-TF",
                            "W3 internal structure = 5 swings on " + childTf + " (confirms impulse)"));
                } else if (childSwings == 3) {
                    wc.getCrossTfScore();  // no boost for 3 swings in W3
                    wc.getContradictingEvidence().add(WaveEvidence.contradicts("Cross-TF",
                            "W3 shows only 3 child-TF swings (corrective internal = lower confidence)"));
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
        score += fibProximityScore(w5 / w1, W5_IDEAL_EXTENSION,  8);
        return score;  // max 40
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
        // W3 should have the highest EWO — momentum peak (adds probability)
        if (w3e.getEwo() > w1e.getEwo() && w3e.getEwo() > w5e.getEwo()) score += 8;
        // W5 should show MACD divergence vs W3 — exhaustion (adds probability)
        if (w5e.getMacdHistogram() < w3e.getMacdHistogram()) score += 8;
        // W3 Bollinger walk — momentum confirmation (adds probability)
        if (w3e.isBollingerWalk()) score += 7;
        // W4 stochastic oversold — correction exhaustion (adds probability)
        if (w4e.getStochK() < 30) score += 7;
        // W4 MACD amplitude contracting vs W3 — W4 signature (adds probability)
        boolean w4MacdContracting = w4e.getMacdHistogramAmplitude() < w3e.getMacdHistogramAmplitude() * 0.5;
        if (w4MacdContracting) score += 8;
        // W4 MACD near zero — consolidation oscillation signature (adds probability)
        if (w4e.isMacdNearZero()) score += 7;
        return score; // max ~45 with new signals
    }

    private int scoreIndicatorsImpulseDown(EnrichedPivot w1e, EnrichedPivot w2e, EnrichedPivot w3e,
                                            EnrichedPivot w4e, EnrichedPivot w5e) {
        int score = 0;
        if (w3e.getEwo() < w1e.getEwo() && w3e.getEwo() < w5e.getEwo()) score += 8;
        if (w5e.getMacdHistogram() > w3e.getMacdHistogram()) score += 8;
        if (w4e.getStochK() > 70) score += 7;
        boolean w4MacdContracting = w4e.getMacdHistogramAmplitude() < w3e.getMacdHistogramAmplitude() * 0.5;
        if (w4MacdContracting) score += 8;
        if (w4e.isMacdNearZero()) score += 7;
        return score;
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
}
