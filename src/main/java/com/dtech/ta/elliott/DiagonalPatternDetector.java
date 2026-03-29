package com.dtech.ta.elliott;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects Leading Diagonal and Ending Diagonal patterns from enriched pivot sequences.
 *
 * Elliott Wave diagonal rules:
 *
 * LEADING_DIAGONAL (at Wave 1 or Wave A start):
 *   - 5 sub-waves, all three-wave structures internally
 *   - Wave 4 overlaps into Wave 1 territory (key distinction from standard impulse)
 *   - Trendlines converge (contracting)
 *   - Sub-waves are progressively shorter (typical) or similar in length
 *   - Followed by a sharp Wave 2 correction, then a strong Wave 3
 *
 * ENDING_DIAGONAL (at Wave 5 or Wave C terminal):
 *   - Same converging structure with Wave 4 overlap
 *   - Wave 5 often truncates (doesn't exceed Wave 3 extreme)
 *   - Signals exhaustion — followed by a sharp reversal
 *   - All sub-waves are three-wave zigzag structures
 *
 * Detection uses the last 6 alternating pivots (5 sub-waves) from the pivot sequence.
 */
public class DiagonalPatternDetector {

    /** Wave 4 must overlap Wave 1 by at least this fraction of wave 1 range */
    private static final double MIN_OVERLAP_FRACTION = 0.01;

    public List<PatternMatch> detect(List<EnrichedPivot> pivots, String timeframe) {
        List<PatternMatch> results = new ArrayList<>();
        if (pivots == null || pivots.size() < 6) return results;

        results.addAll(detectBullishLeadingDiagonal(pivots, timeframe));
        results.addAll(detectBearishEndingDiagonal(pivots, timeframe));

        return results;
    }

    // ── Bullish Leading Diagonal ─────────────────────────────────────────────

    /**
     * Bullish LD: pivot sequence LOW → HIGH → LOW → HIGH → LOW → HIGH
     * Wave 4 (p4, a LOW) must dip below Wave 1 top (p1, a HIGH).
     * Trendlines connecting highs (p1, p3, p5) and lows (p0, p2, p4) must converge.
     */
    private List<PatternMatch> detectBullishLeadingDiagonal(List<EnrichedPivot> pivots, String timeframe) {
        List<PatternMatch> results = new ArrayList<>();

        // Look at last 6 pivots
        int n = pivots.size();
        EnrichedPivot p0 = pivots.get(n - 6);
        EnrichedPivot p1 = pivots.get(n - 5);
        EnrichedPivot p2 = pivots.get(n - 4);
        EnrichedPivot p3 = pivots.get(n - 3);
        EnrichedPivot p4 = pivots.get(n - 2);
        EnrichedPivot p5 = pivots.get(n - 1);

        if (!p0.isLow() || !p1.isHigh() || !p2.isLow()
                || !p3.isHigh() || !p4.isLow() || !p5.isHigh()) return results;

        // All lows must be rising (corrective bounce structure)
        if (!(p2.getPrice() > p0.getPrice() && p4.getPrice() > p2.getPrice())) return results;
        // All highs must be rising
        if (!(p3.getPrice() > p1.getPrice() && p5.getPrice() > p3.getPrice())) return results;
        // Wave 2 must retrace less than 100% of wave 1 (p2 stays above p0)
        if (p2.getPrice() <= p0.getPrice()) return results;

        // KEY RULE: Wave 4 (p4) overlaps Wave 1 (p1) territory
        // i.e., p4.price < p1.price (wave 4 low is below wave 1 high)
        double w1Height = p1.getPrice() - p0.getPrice();
        double overlap = p1.getPrice() - p4.getPrice();
        if (overlap < w1Height * MIN_OVERLAP_FRACTION) return results;

        // Trendline convergence: slope of highs > slope of lows (in absolute terms, both positive)
        double highSlope = (p3.getPrice() - p1.getPrice()) / 2.0;
        double lowSlope  = (p4.getPrice() - p2.getPrice()) / 2.0;
        if (highSlope <= lowSlope) return results; // not converging

        double confidence = computeBullishLdConfidence(p0, p1, p2, p3, p4, p5);

        results.add(PatternMatch.builder()
                .type(PatternType.LEADING_DIAGONAL)
                .status(PatternStatus.WATCHING)
                .timeframe(timeframe)
                .support(p4.getPrice())
                .resistance(p5.getPrice())
                .confidence(confidence)
                .converging(true)
                .description(String.format(
                        "Bullish leading diagonal: 5-wave converging structure with wave-4 overlap (%.2f < %.2f). "
                        + "Suggests Wave 1 of a new impulse leg — expect Wave 2 pullback, then strong Wave 3.",
                        p4.getPrice(), p1.getPrice()))
                .waveContextHints(List.of(
                        WaveContextHint.builder()
                                .impliedCurrentPosition(WaveLabel.W1)
                                .impliedNextWave(WaveLabel.W2)
                                .probability(0.65)
                                .confirmationLevel(p4.getPrice())
                                .confirmationDescription("Hold above " + String.format("%.2f", p4.getPrice())
                                        + " confirms wave 2 pullback zone")
                                .narrative("Leading diagonal at Wave 1: wave 4 overlap confirms 3-wave sub-structure. "
                                        + "After wave 5 completes, expect Wave 2 retracement (50–78.6% of diagonal), "
                                        + "then a strong impulsive Wave 3.")
                                .build(),
                        WaveContextHint.builder()
                                .impliedCurrentPosition(WaveLabel.WA)
                                .impliedNextWave(WaveLabel.WB)
                                .probability(0.35)
                                .narrative("Alternatively, leading diagonal as Wave A of a zigzag — Wave B bounce coming.")
                                .build()
                ))
                .build());

        return results;
    }

    // ── Bearish Ending Diagonal ──────────────────────────────────────────────

    /**
     * Bearish EDT at Wave C or Wave 5 terminal:
     * pivot sequence HIGH → LOW → HIGH → LOW → HIGH → LOW (declining)
     * Wave 4 (p4, a HIGH) must rise above Wave 1 bottom (p1, a LOW).
     * Trendlines connecting lows and highs converge downward.
     * Wave 5 (p5) may truncate (not go below p3).
     */
    private List<PatternMatch> detectBearishEndingDiagonal(List<EnrichedPivot> pivots, String timeframe) {
        List<PatternMatch> results = new ArrayList<>();

        int n = pivots.size();
        EnrichedPivot p0 = pivots.get(n - 6);
        EnrichedPivot p1 = pivots.get(n - 5);
        EnrichedPivot p2 = pivots.get(n - 4);
        EnrichedPivot p3 = pivots.get(n - 3);
        EnrichedPivot p4 = pivots.get(n - 2);
        EnrichedPivot p5 = pivots.get(n - 1);

        if (!p0.isHigh() || !p1.isLow() || !p2.isHigh()
                || !p3.isLow() || !p4.isHigh() || !p5.isLow()) return results;

        // All highs must be declining
        if (!(p2.getPrice() < p0.getPrice() && p4.getPrice() < p2.getPrice())) return results;
        // All lows must be declining
        if (!(p3.getPrice() < p1.getPrice() && p5.getPrice() < p3.getPrice())) return results;
        // Wave 2 retraces < 100% of wave 1 (p2 stays below p0)
        if (p2.getPrice() >= p0.getPrice()) return results;

        // KEY RULE: Wave 4 (p4) overlaps Wave 1 (p1) territory
        // i.e., p4.price > p1.price (wave 4 high is above wave 1 low)
        double w1Height = p0.getPrice() - p1.getPrice();
        double overlap = p4.getPrice() - p1.getPrice();
        if (overlap < w1Height * MIN_OVERLAP_FRACTION) return results;

        // Trendline convergence: both trendlines sloping down, but lows slope is steeper than highs
        double highSlope = (p2.getPrice() - p0.getPrice()) / 2.0; // negative
        double lowSlope  = (p3.getPrice() - p1.getPrice()) / 2.0; // negative
        // For convergence: lowSlope must be more negative than highSlope
        if (lowSlope >= highSlope) return results;

        boolean wave5Truncated = p5.getPrice() > p3.getPrice();
        double confidence = computeBearishEdtConfidence(p0, p1, p2, p3, p4, p5, wave5Truncated);

        String truncationNote = wave5Truncated
                ? String.format(" Wave 5 TRUNCATED (%.2f > %.2f) — terminal exhaustion signal.", p5.getPrice(), p3.getPrice())
                : "";

        results.add(PatternMatch.builder()
                .type(PatternType.ENDING_DIAGONAL)
                .status(PatternStatus.WATCHING)
                .timeframe(timeframe)
                .support(p5.getPrice())
                .resistance(p4.getPrice())
                .confidence(confidence)
                .converging(true)
                .description(String.format(
                        "Bearish ending diagonal: terminal 5-wave contracting structure with wave-4 overlap (%.2f > %.2f).%s "
                        + "Signals final exhaustion — sharp reversal rally expected once complete.",
                        p4.getPrice(), p1.getPrice(), truncationNote))
                .waveContextHints(List.of(
                        WaveContextHint.builder()
                                .impliedCurrentPosition(WaveLabel.WC)
                                .impliedNextWave(WaveLabel.UNKNOWN)
                                .probability(wave5Truncated ? 0.80 : 0.65)
                                .confirmationLevel(p4.getPrice())
                                .confirmationDescription("Break above " + String.format("%.2f", p4.getPrice())
                                        + " (wave 4 high) confirms EDT complete — buy signal")
                                .narrative("Ending diagonal at Wave C terminal: all sub-waves are 3-wave zigzags. "
                                        + "Completion followed by sharp reversal rally. "
                                        + "Invalidated if price breaks below prior wave 3 low.")
                                .build(),
                        WaveContextHint.builder()
                                .impliedCurrentPosition(WaveLabel.W5)
                                .impliedNextWave(WaveLabel.UNKNOWN)
                                .probability(0.30)
                                .narrative("Alternatively, EDT at Wave 5 of a larger impulse — same reversal implication.")
                                .build()
                ))
                .build());

        return results;
    }

    // ── Confidence scoring ───────────────────────────────────────────────────

    private double computeBullishLdConfidence(EnrichedPivot p0, EnrichedPivot p1,
            EnrichedPivot p2, EnrichedPivot p3, EnrichedPivot p4, EnrichedPivot p5) {
        double conf = 50.0;

        double w1 = p1.getPrice() - p0.getPrice();
        double w2 = p1.getPrice() - p2.getPrice();
        double w3 = p3.getPrice() - p2.getPrice();
        double w5 = p5.getPrice() - p4.getPrice();

        // Wave 3 is the longest impulse sub-wave
        if (w3 > w1 && w3 > w5) conf += 10;
        // Wave 2 retraces 50–78.6% of wave 1
        double w2Retrace = w2 / w1;
        if (w2Retrace >= 0.50 && w2Retrace <= 0.786) conf += 10;
        // Each wave is progressively shorter (contracting)
        if (w5 < w3 && w3 < w1 * 1.618) conf += 10;
        // RSI divergence on last high (p5 RSI < p3 RSI) — bearish divergence within LD
        if (p5.getRsi() < p3.getRsi()) conf += 10;
        // Volume confirmation (lower volume on later waves)
        if (p5.getVolume() < p3.getVolume()) conf += 5;

        return Math.min(conf, 90.0);
    }

    private double computeBearishEdtConfidence(EnrichedPivot p0, EnrichedPivot p1,
            EnrichedPivot p2, EnrichedPivot p3, EnrichedPivot p4, EnrichedPivot p5,
            boolean truncated) {
        double conf = 50.0;

        double w1 = p0.getPrice() - p1.getPrice();
        double w3 = p2.getPrice() - p3.getPrice();
        double w5 = p4.getPrice() - p5.getPrice();

        // Wave 3 is not the shortest
        if (w3 >= w1 * 0.5) conf += 10;
        // Each wave shorter (contracting EDT)
        if (w5 < w3 && w3 < w1 * 1.5) conf += 10;
        // Truncated wave 5 — strong signal
        if (truncated) conf += 15;
        // Bearish RSI divergence (higher price at p4 vs p2 but lower RSI)
        if (p4.getRsi() < p2.getRsi()) conf += 10;

        return Math.min(conf, 90.0);
    }
}
