package com.dtech.ta.elliott;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects chart patterns from a sequence of enriched ZigZag pivots.
 *
 * Unlike the legacy candle-based detectors, this works on the pivot sequence directly —
 * O(N) where N is the number of pivots, not candles. It handles both BUILDING patterns
 * (first pivot just formed, opportunity alert) and COMPLETE patterns (breakout ready).
 *
 * Patterns detected:
 *  - Double Bottom / Top (BUILDING after 1st pivot, WATCHING after 2nd, CONFIRMED on neckline break)
 *  - Triple Bottom / Top
 *  - Head & Shoulders / Inverted H&S
 *  - Ascending / Descending / Symmetrical Triangle
 *  - Rising / Falling Wedge
 *  - Bull / Bear Flag and Pennant
 *  - Ascending / Descending Channel
 *  - Elliott corrective patterns (Zigzag, Flat, Expanded Flat)
 *  - Diagonal patterns (Leading Diagonal, Ending Diagonal / EDT)
 */
@Service
public class PivotPatternDetector {

    /** Max % difference allowed between two "equal" price levels (e.g., double bottom lows) */
    private static final double EQUAL_LEVEL_TOLERANCE = 0.04;

    /** Min retracement between two bottoms to form a valid neckline */
    private static final double MIN_NECKLINE_RETRACE = 0.05;

    /** Fibonacci zones for B-wave to distinguish zigzag from flat */
    private static final double FLAT_B_RETRACE_MIN = 0.90;
    private static final double ZIGZAG_B_RETRACE_MAX = 0.786;

    private final DiagonalPatternDetector diagonalDetector = new DiagonalPatternDetector();

    public List<PatternMatch> detect(List<EnrichedPivot> pivots, String timeframe) {
        List<PatternMatch> results = new ArrayList<>();

        results.addAll(detectDoubleBottoms(pivots, timeframe));
        results.addAll(detectDoubleTops(pivots, timeframe));
        results.addAll(detectTripleBottoms(pivots, timeframe));
        results.addAll(detectTripleTops(pivots, timeframe));
        results.addAll(detectHeadAndShoulders(pivots, timeframe));
        results.addAll(detectInvertedHeadAndShoulders(pivots, timeframe));
        results.addAll(detectTriangles(pivots, timeframe));
        results.addAll(detectWedges(pivots, timeframe));
        results.addAll(detectFlags(pivots, timeframe));
        results.addAll(detectChannels(pivots, timeframe));
        results.addAll(detectCorrectivePatterns(pivots, timeframe));
        results.addAll(diagonalDetector.detect(pivots, timeframe));

        return results;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // WAVE CONTEXT HINTS — reverse inference: pattern → wave position
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * For each pattern type, returns the most likely Elliott Wave positions it implies.
     * These are probabilistic hints, not verdicts.
     * Rule: indicators boost probability but never deny a pattern.
     */
    private List<WaveContextHint> hintsFor(PatternType type, EnrichedPivot lastPivot,
                                            Double confirmationLevel) {
        return switch (type) {

            case FALLING_WEDGE -> List.of(
                WaveContextHint.builder()
                    .impliedCurrentPosition(WaveLabel.W5)
                    .impliedNextWave(WaveLabel.UNKNOWN)   // W5 complete → reversal
                    .probability(0.75)
                    .confirmationLevel(confirmationLevel)
                    .confirmationDescription("Close above upper wedge line confirms terminal W5 complete")
                    .narrative("Falling wedge = ending diagonal. Terminal wave completing → sharp reversal up imminent.")
                    .build(),
                WaveContextHint.builder()
                    .impliedCurrentPosition(WaveLabel.WA)
                    .impliedNextWave(WaveLabel.WB)
                    .subwaveHint(true)
                    .parentWave(WaveLabel.WA)
                    .probability(0.70)
                    .confirmationLevel(confirmationLevel)
                    .confirmationDescription("Wedge break = Wave A of correction complete, B-wave bounce imminent")
                    .narrative("Falling wedge = impulsive Wave A sub-wave 5 (ending diagonal) → Wave A complete, B bounce next.")
                    .build()
            );

            case RISING_WEDGE -> List.of(
                WaveContextHint.builder()
                    .impliedCurrentPosition(WaveLabel.W5)
                    .impliedNextWave(WaveLabel.UNKNOWN)
                    .probability(0.75)
                    .confirmationLevel(confirmationLevel)
                    .confirmationDescription("Close below lower wedge line confirms W5 exhaustion")
                    .narrative("Rising wedge = ending diagonal at W5 top. Sharp reversal down when broken.")
                    .build(),
                WaveContextHint.builder()
                    .impliedCurrentPosition(WaveLabel.WC)
                    .impliedNextWave(WaveLabel.UNKNOWN)
                    .probability(0.60)
                    .confirmationLevel(confirmationLevel)
                    .confirmationDescription("Break below wedge support confirms Wave C terminal diagonal complete")
                    .narrative("Rising wedge at correction peak = Wave C terminal. Full correction complete on breakdown.")
                    .build()
            );

            case SYMMETRICAL_TRIANGLE, ASCENDING_TRIANGLE, DESCENDING_TRIANGLE -> List.of(
                WaveContextHint.builder()
                    .impliedCurrentPosition(WaveLabel.W4)
                    .impliedNextWave(WaveLabel.W5)
                    .probability(0.80)
                    .confirmationLevel(confirmationLevel)
                    .confirmationDescription("Triangle apex break = W4 complete, W5 launch begins")
                    .narrative("Triangle = classic W4 structure (Elliott Rule: W4 often forms triangle). W5 expected on breakout.")
                    .build(),
                WaveContextHint.builder()
                    .impliedCurrentPosition(WaveLabel.WB)
                    .impliedNextWave(WaveLabel.WC)
                    .probability(0.55)
                    .confirmationLevel(confirmationLevel)
                    .confirmationDescription("Triangle in B-wave position → C-wave follows on breakout")
                    .narrative("Triangle as B-wave: complex B followed by sharp C in same direction as A.")
                    .build()
            );

            case DOUBLE_BOTTOM, TRIPLE_BOTTOM, INVERTED_HEAD_AND_SHOULDERS -> List.of(
                WaveContextHint.builder()
                    .impliedCurrentPosition(WaveLabel.W2)
                    .impliedNextWave(WaveLabel.W3)
                    .probability(0.70)
                    .confirmationLevel(confirmationLevel)
                    .confirmationDescription("Neckline break = W2 complete, W3 launching")
                    .narrative("Bullish reversal pattern at low = W2 bottom. Neckline break confirms W3 momentum impulse starting.")
                    .build(),
                WaveContextHint.builder()
                    .impliedCurrentPosition(WaveLabel.W4)
                    .impliedNextWave(WaveLabel.W5)
                    .probability(0.60)
                    .confirmationLevel(confirmationLevel)
                    .confirmationDescription("Neckline break = W4 bottom confirmed, W5 beginning")
                    .narrative("Bullish reversal at W4 support = launchpad for final W5.")
                    .build(),
                WaveContextHint.builder()
                    .impliedCurrentPosition(WaveLabel.WC)
                    .impliedNextWave(WaveLabel.W1)
                    .probability(0.55)
                    .confirmationLevel(confirmationLevel)
                    .confirmationDescription("Neckline break = full correction (ABC) complete, new impulse beginning")
                    .narrative("Bullish reversal at WC bottom = entire corrective sequence complete. New impulse likely.")
                    .build()
            );

            case DOUBLE_TOP, TRIPLE_TOP, HEAD_AND_SHOULDERS -> List.of(
                WaveContextHint.builder()
                    .impliedCurrentPosition(WaveLabel.W5)
                    .impliedNextWave(WaveLabel.WA)
                    .probability(0.72)
                    .confirmationLevel(confirmationLevel)
                    .confirmationDescription("Neckline break = W5 top confirmed, Wave A correction starting")
                    .narrative("Bearish reversal at top = W5 exhaustion. Neckline break starts A-B-C correction.")
                    .build(),
                WaveContextHint.builder()
                    .impliedCurrentPosition(WaveLabel.WB)
                    .impliedNextWave(WaveLabel.WC)
                    .probability(0.55)
                    .confirmationLevel(confirmationLevel)
                    .confirmationDescription("Top pattern at B-wave high confirms B complete, C decline next")
                    .narrative("Bearish reversal at B-wave top: B corrective bounce exhausted, C decline resumes.")
                    .build()
            );

            case BULL_FLAG, BULL_PENNANT -> List.of(
                WaveContextHint.builder()
                    .impliedCurrentPosition(WaveLabel.W4)
                    .impliedNextWave(WaveLabel.W5)
                    .probability(0.78)
                    .confirmationLevel(confirmationLevel)
                    .confirmationDescription("Flag breakout = W4 complete, W5 target = flag pole projection")
                    .narrative("Bull flag/pennant = W4 brief consolidation. Breakout launches W5 with measured move target.")
                    .build()
            );

            case BEAR_FLAG, BEAR_PENNANT -> List.of(
                WaveContextHint.builder()
                    .impliedCurrentPosition(WaveLabel.W4)
                    .impliedNextWave(WaveLabel.W5)
                    .probability(0.78)
                    .confirmationLevel(confirmationLevel)
                    .confirmationDescription("Bear flag breakdown = W4 of bearish impulse complete, W5 down next")
                    .narrative("Bear flag/pennant = W4 counter-rally exhausted. Breakdown resumes bearish W5.")
                    .build()
            );

            case ASCENDING_CHANNEL -> List.of(
                WaveContextHint.builder()
                    .impliedCurrentPosition(WaveLabel.W3)
                    .impliedNextWave(WaveLabel.W4)
                    .probability(0.65)
                    .narrative("Ascending channel = typical W3 impulse channel. Channel break signals W3 top, W4 correction starting.")
                    .build()
            );

            case DESCENDING_CHANNEL -> List.of(
                WaveContextHint.builder()
                    .impliedCurrentPosition(WaveLabel.WA)
                    .impliedNextWave(WaveLabel.WB)
                    .probability(0.68)
                    .confirmationLevel(confirmationLevel)
                    .confirmationDescription("Channel top break = Wave A complete, B-wave bounce in progress")
                    .narrative("Descending channel = Wave A of ABC correction (5 sub-waves down). Channel break = A complete, B bounce next.")
                    .build()
            );

            case ELLIOTT_ZIGZAG -> List.of(
                WaveContextHint.builder()
                    .impliedCurrentPosition(WaveLabel.W2)
                    .impliedNextWave(WaveLabel.W3)
                    .probability(0.72)
                    .narrative("Zigzag ABC = sharp W2 correction (typical zigzag form). W3 impulse follows.")
                    .build(),
                WaveContextHint.builder()
                    .impliedCurrentPosition(WaveLabel.W4)
                    .impliedNextWave(WaveLabel.W5)
                    .probability(0.60)
                    .narrative("Zigzag ABC in W4 position (alternation: if W2 was flat, W4 is often zigzag). W5 follows.")
                    .build()
            );

            case ELLIOTT_FLAT -> List.of(
                WaveContextHint.builder()
                    .impliedCurrentPosition(WaveLabel.W4)
                    .impliedNextWave(WaveLabel.W5)
                    .probability(0.75)
                    .narrative("Flat ABC = sideways W4 correction (most common W4 form). W5 follows.")
                    .build(),
                WaveContextHint.builder()
                    .impliedCurrentPosition(WaveLabel.W2)
                    .impliedNextWave(WaveLabel.W3)
                    .probability(0.55)
                    .narrative("Flat ABC as W2 (less common but valid). W3 impulse follows.")
                    .build()
            );

            case ELLIOTT_EXPANDED_FLAT -> List.of(
                WaveContextHint.builder()
                    .impliedCurrentPosition(WaveLabel.W4)
                    .impliedNextWave(WaveLabel.W5)
                    .probability(0.70)
                    .narrative("Expanded flat = W4 correction where B exceeds W3 high. Indicates strong bull trend — W5 extension likely.")
                    .build()
            );

            case LEADING_DIAGONAL -> List.of(
                WaveContextHint.builder()
                    .impliedCurrentPosition(WaveLabel.W1)
                    .impliedNextWave(WaveLabel.W2)
                    .probability(0.65)
                    .confirmationLevel(confirmationLevel)
                    .confirmationDescription("Wave 4 support holds → wave 5 of diagonal completing; then sharp Wave 2 pullback before Wave 3")
                    .narrative("Leading diagonal = Wave 1 of new impulse. Expect deep Wave 2 retrace (50–78.6%) before Wave 3 extends.")
                    .build(),
                WaveContextHint.builder()
                    .impliedCurrentPosition(WaveLabel.WA)
                    .impliedNextWave(WaveLabel.WB)
                    .probability(0.35)
                    .narrative("Leading diagonal as Wave A of corrective zigzag — Wave B bounce imminent after completion.")
                    .build()
            );

            case ENDING_DIAGONAL -> List.of(
                WaveContextHint.builder()
                    .impliedCurrentPosition(WaveLabel.WC)
                    .impliedNextWave(WaveLabel.UNKNOWN)
                    .probability(0.70)
                    .confirmationLevel(confirmationLevel)
                    .confirmationDescription("Break above wave 4 high of diagonal confirms EDT complete — sharp reversal rally begins")
                    .narrative("Ending diagonal at Wave C: terminal exhaustion structure. Completion followed by sharp reversal.")
                    .build(),
                WaveContextHint.builder()
                    .impliedCurrentPosition(WaveLabel.W5)
                    .impliedNextWave(WaveLabel.UNKNOWN)
                    .probability(0.30)
                    .narrative("Ending diagonal at Wave 5: impulse terminal. Full A-B-C correction begins on breakdown.")
                    .build()
            );

            default -> List.of();
        };
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DOUBLE BOTTOM / TOP
    // ══════════════════════════════════════════════════════════════════════════

    private List<PatternMatch> detectDoubleBottoms(List<EnrichedPivot> pivots, String timeframe) {
        List<PatternMatch> results = new ArrayList<>();
        List<EnrichedPivot> lows = filterLows(pivots);

        for (int i = 0; i < lows.size(); i++) {
            EnrichedPivot l1 = lows.get(i);

            // BUILDING: first low found — is there a bounce (high) after it?
            EnrichedPivot h1 = firstHighAfter(pivots, l1.getBarIndex());
            if (h1 == null) {
                // L1 is the latest pivot — very early stage
                results.add(PatternMatch.builder()
                        .type(PatternType.DOUBLE_BOTTOM)
                        .status(PatternStatus.BUILDING)
                        .timeframe(timeframe)
                        .support(l1.getPrice())
                        .waitingFor("Bounce from " + fmt(l1.getPrice()) + " then retest for Double Bottom")
                        .watchLevel(l1.getPrice())
                        .watchTolerance(EQUAL_LEVEL_TOLERANCE)
                        .pivotBarIndices(List.of(l1.getBarIndex()))
                        .pivotPrices(List.of(l1.getPrice()))
                        .pivotTimestamps(List.of(l1.getTimestamp()))
                        .invalidation(l1.getPrice() * 0.97)
                        .confidence(25)
                        .description("Double Bottom BUILDING — first low at " + fmt(l1.getPrice()))
                        .build());
                continue;
            }

            double neckline = h1.getPrice();
            double retrace = (neckline - l1.getPrice()) / neckline;
            if (retrace < MIN_NECKLINE_RETRACE) continue;

            // Look for L2 after H1 — must be within tolerance of L1
            List<EnrichedPivot> candidateL2s = lowsAfter(pivots, h1.getBarIndex());
            for (EnrichedPivot l2 : candidateL2s) {
                if (!withinTolerance(l1.getPrice(), l2.getPrice(), EQUAL_LEVEL_TOLERANCE)) continue;

                double patternHeight = neckline - Math.min(l1.getPrice(), l2.getPrice());
                double target = neckline + patternHeight;
                boolean rsiDiv = l2.getRsi() > l1.getRsi() && l2.getPrice() <= l1.getPrice() * 1.03;
                boolean macdDiv = l2.getMacdHistogram() > l1.getMacdHistogram();
                double confidence = 60
                        + (rsiDiv ? 10 : 0)
                        + (macdDiv ? 10 : 0)
                        + (l2.getAdx() < 25 ? 5 : 0);   // low ADX = range = good for reversal

                EnrichedPivot h2 = firstHighAfter(pivots, l2.getBarIndex());
                PatternStatus status = (h2 != null && h2.getPrice() >= neckline)
                        ? PatternStatus.CONFIRMED : PatternStatus.WATCHING;

                List<WaveContextHint> hints = hintsFor(PatternType.DOUBLE_BOTTOM, l2, neckline);

                results.add(PatternMatch.builder()
                        .type(PatternType.DOUBLE_BOTTOM)
                        .status(status)
                        .timeframe(timeframe)
                        .support(Math.min(l1.getPrice(), l2.getPrice()))
                        .neckline(neckline)
                        .target(target)
                        .partialTarget(neckline + patternHeight * 0.5)
                        .invalidation(Math.min(l1.getPrice(), l2.getPrice()) * 0.97)
                        .waitingFor(status == PatternStatus.WATCHING
                                ? "Close above neckline " + fmt(neckline) + " to confirm"
                                : null)
                        .pivotBarIndices(List.of(l1.getBarIndex(), h1.getBarIndex(), l2.getBarIndex()))
                        .pivotPrices(List.of(l1.getPrice(), h1.getPrice(), l2.getPrice()))
                        .pivotTimestamps(List.of(l1.getTimestamp(), h1.getTimestamp(), l2.getTimestamp()))
                        .rsiDivergence(rsiDiv)
                        .macdDivergence(macdDiv)
                        .waveContextHints(hints)
                        .confirmationLevel(neckline)
                        .confidence(Math.min(confidence, 95))
                        .description("Double Bottom at " + fmt(l1.getPrice()) + "/" + fmt(l2.getPrice())
                                + " neckline " + fmt(neckline) + " target " + fmt(target))
                        .build());
            }
        }
        return results;
    }

    private List<PatternMatch> detectDoubleTops(List<EnrichedPivot> pivots, String timeframe) {
        List<PatternMatch> results = new ArrayList<>();
        List<EnrichedPivot> highs = filterHighs(pivots);

        for (int i = 0; i < highs.size(); i++) {
            EnrichedPivot h1 = highs.get(i);

            EnrichedPivot l1 = firstLowAfter(pivots, h1.getBarIndex());
            if (l1 == null) {
                results.add(PatternMatch.builder()
                        .type(PatternType.DOUBLE_TOP)
                        .status(PatternStatus.BUILDING)
                        .timeframe(timeframe)
                        .resistance(h1.getPrice())
                        .waitingFor("Pullback from " + fmt(h1.getPrice()) + " then retest for Double Top")
                        .watchLevel(h1.getPrice())
                        .watchTolerance(EQUAL_LEVEL_TOLERANCE)
                        .pivotBarIndices(List.of(h1.getBarIndex()))
                        .pivotPrices(List.of(h1.getPrice()))
                        .pivotTimestamps(List.of(h1.getTimestamp()))
                        .invalidation(h1.getPrice() * 1.03)
                        .confidence(25)
                        .description("Double Top BUILDING — first high at " + fmt(h1.getPrice()))
                        .build());
                continue;
            }

            double neckline = l1.getPrice();
            List<EnrichedPivot> candidateH2s = highsAfter(pivots, l1.getBarIndex());
            for (EnrichedPivot h2 : candidateH2s) {
                if (!withinTolerance(h1.getPrice(), h2.getPrice(), EQUAL_LEVEL_TOLERANCE)) continue;

                double patternHeight = Math.max(h1.getPrice(), h2.getPrice()) - neckline;
                double target = neckline - patternHeight;
                boolean rsiDiv = h2.getRsi() < h1.getRsi() && h2.getPrice() >= h1.getPrice() * 0.97;
                boolean macdDiv = h2.getMacdHistogram() < h1.getMacdHistogram();
                double confidence = 60 + (rsiDiv ? 10 : 0) + (macdDiv ? 10 : 0);

                EnrichedPivot l2 = firstLowAfter(pivots, h2.getBarIndex());
                PatternStatus status = (l2 != null && l2.getPrice() <= neckline)
                        ? PatternStatus.CONFIRMED : PatternStatus.WATCHING;

                List<WaveContextHint> hints = hintsFor(PatternType.DOUBLE_TOP, h2, neckline);

                results.add(PatternMatch.builder()
                        .type(PatternType.DOUBLE_TOP)
                        .status(status)
                        .timeframe(timeframe)
                        .resistance(Math.max(h1.getPrice(), h2.getPrice()))
                        .neckline(neckline)
                        .target(target)
                        .partialTarget(neckline - patternHeight * 0.5)
                        .invalidation(Math.max(h1.getPrice(), h2.getPrice()) * 1.03)
                        .waitingFor(status == PatternStatus.WATCHING
                                ? "Close below neckline " + fmt(neckline) + " to confirm"
                                : null)
                        .pivotBarIndices(List.of(h1.getBarIndex(), l1.getBarIndex(), h2.getBarIndex()))
                        .pivotPrices(List.of(h1.getPrice(), l1.getPrice(), h2.getPrice()))
                        .pivotTimestamps(List.of(h1.getTimestamp(), l1.getTimestamp(), h2.getTimestamp()))
                        .rsiDivergence(rsiDiv)
                        .macdDivergence(macdDiv)
                        .waveContextHints(hints)
                        .confirmationLevel(neckline)
                        .confidence(Math.min(confidence, 95))
                        .description("Double Top at " + fmt(h1.getPrice()) + "/" + fmt(h2.getPrice())
                                + " neckline " + fmt(neckline) + " target " + fmt(target))
                        .build());
            }
        }
        return results;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TRIPLE BOTTOM / TOP
    // ══════════════════════════════════════════════════════════════════════════

    private List<PatternMatch> detectTripleBottoms(List<EnrichedPivot> pivots, String timeframe) {
        List<PatternMatch> results = new ArrayList<>();
        List<EnrichedPivot> lows = filterLows(pivots);

        for (int i = 0; i + 2 < lows.size(); i++) {
            EnrichedPivot l1 = lows.get(i);
            EnrichedPivot l2 = lows.get(i + 1);
            EnrichedPivot l3 = lows.get(i + 2);
            if (!withinTolerance(l1.getPrice(), l2.getPrice(), EQUAL_LEVEL_TOLERANCE)) continue;
            if (!withinTolerance(l2.getPrice(), l3.getPrice(), EQUAL_LEVEL_TOLERANCE)) continue;

            EnrichedPivot h1 = firstHighAfter(pivots, l1.getBarIndex());
            EnrichedPivot h2 = firstHighAfter(pivots, l2.getBarIndex());
            if (h1 == null || h2 == null) continue;

            double neckline = Math.max(h1.getPrice(), h2.getPrice());
            double patternHeight = neckline - avg(l1.getPrice(), l2.getPrice(), l3.getPrice());
            double target = neckline + patternHeight;
            boolean rsiDiv = l3.getRsi() > l1.getRsi();

            EnrichedPivot h3 = firstHighAfter(pivots, l3.getBarIndex());
            PatternStatus status = (h3 != null && h3.getPrice() >= neckline)
                    ? PatternStatus.CONFIRMED : PatternStatus.WATCHING;

            List<WaveContextHint> hints = hintsFor(PatternType.TRIPLE_BOTTOM, l3, neckline);

            results.add(PatternMatch.builder()
                    .type(PatternType.TRIPLE_BOTTOM)
                    .status(status)
                    .timeframe(timeframe)
                    .support(Math.min(l1.getPrice(), Math.min(l2.getPrice(), l3.getPrice())))
                    .neckline(neckline)
                    .target(target)
                    .partialTarget(neckline + patternHeight * 0.5)
                    .invalidation(Math.min(l1.getPrice(), Math.min(l2.getPrice(), l3.getPrice())) * 0.97)
                    .pivotBarIndices(List.of(l1.getBarIndex(), h1.getBarIndex(), l2.getBarIndex(), h2.getBarIndex(), l3.getBarIndex()))
                    .pivotPrices(List.of(l1.getPrice(), h1.getPrice(), l2.getPrice(), h2.getPrice(), l3.getPrice()))
                    .pivotTimestamps(List.of(l1.getTimestamp(), h1.getTimestamp(), l2.getTimestamp(), h2.getTimestamp(), l3.getTimestamp()))
                    .rsiDivergence(rsiDiv)
                    .waveContextHints(hints)
                    .confirmationLevel(neckline)
                    .confidence(Math.min(70 + (rsiDiv ? 15 : 0), 95))
                    .description("Triple Bottom at ~" + fmt(l1.getPrice()) + " neckline " + fmt(neckline))
                    .build());
        }
        return results;
    }

    private List<PatternMatch> detectTripleTops(List<EnrichedPivot> pivots, String timeframe) {
        List<PatternMatch> results = new ArrayList<>();
        List<EnrichedPivot> highs = filterHighs(pivots);

        for (int i = 0; i + 2 < highs.size(); i++) {
            EnrichedPivot h1 = highs.get(i);
            EnrichedPivot h2 = highs.get(i + 1);
            EnrichedPivot h3 = highs.get(i + 2);
            if (!withinTolerance(h1.getPrice(), h2.getPrice(), EQUAL_LEVEL_TOLERANCE)) continue;
            if (!withinTolerance(h2.getPrice(), h3.getPrice(), EQUAL_LEVEL_TOLERANCE)) continue;

            EnrichedPivot l1 = firstLowAfter(pivots, h1.getBarIndex());
            EnrichedPivot l2 = firstLowAfter(pivots, h2.getBarIndex());
            if (l1 == null || l2 == null) continue;

            double neckline = Math.min(l1.getPrice(), l2.getPrice());
            double patternHeight = avg(h1.getPrice(), h2.getPrice(), h3.getPrice()) - neckline;
            double target = neckline - patternHeight;
            boolean rsiDiv = h3.getRsi() < h1.getRsi();

            EnrichedPivot l3 = firstLowAfter(pivots, h3.getBarIndex());
            PatternStatus status = (l3 != null && l3.getPrice() <= neckline)
                    ? PatternStatus.CONFIRMED : PatternStatus.WATCHING;

            List<WaveContextHint> hints = hintsFor(PatternType.TRIPLE_TOP, h3, neckline);

            results.add(PatternMatch.builder()
                    .type(PatternType.TRIPLE_TOP)
                    .status(status)
                    .timeframe(timeframe)
                    .resistance(Math.max(h1.getPrice(), Math.max(h2.getPrice(), h3.getPrice())))
                    .neckline(neckline)
                    .target(target)
                    .partialTarget(neckline - patternHeight * 0.5)
                    .invalidation(Math.max(h1.getPrice(), Math.max(h2.getPrice(), h3.getPrice())) * 1.03)
                    .pivotBarIndices(List.of(h1.getBarIndex(), l1.getBarIndex(), h2.getBarIndex(), l2.getBarIndex(), h3.getBarIndex()))
                    .pivotPrices(List.of(h1.getPrice(), l1.getPrice(), h2.getPrice(), l2.getPrice(), h3.getPrice()))
                    .pivotTimestamps(List.of(h1.getTimestamp(), l1.getTimestamp(), h2.getTimestamp(), l2.getTimestamp(), h3.getTimestamp()))
                    .rsiDivergence(rsiDiv)
                    .waveContextHints(hints)
                    .confirmationLevel(neckline)
                    .confidence(Math.min(70 + (rsiDiv ? 15 : 0), 95))
                    .description("Triple Top at ~" + fmt(h1.getPrice()) + " neckline " + fmt(neckline))
                    .build());
        }
        return results;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HEAD & SHOULDERS
    // ══════════════════════════════════════════════════════════════════════════

    private List<PatternMatch> detectHeadAndShoulders(List<EnrichedPivot> pivots, String timeframe) {
        List<PatternMatch> results = new ArrayList<>();
        List<EnrichedPivot> highs = filterHighs(pivots);

        for (int i = 0; i + 2 < highs.size(); i++) {
            EnrichedPivot ls = highs.get(i);       // left shoulder
            EnrichedPivot head = highs.get(i + 1); // head
            EnrichedPivot rs = highs.get(i + 2);   // right shoulder

            // Head must be the highest
            if (head.getPrice() <= ls.getPrice() || head.getPrice() <= rs.getPrice()) continue;
            // Shoulders approximately equal (within tolerance)
            if (!withinTolerance(ls.getPrice(), rs.getPrice(), EQUAL_LEVEL_TOLERANCE * 1.5)) continue;

            EnrichedPivot lt = firstLowAfter(pivots, ls.getBarIndex());  // left trough
            EnrichedPivot rt = firstLowAfter(pivots, head.getBarIndex()); // right trough
            if (lt == null || rt == null) continue;

            // Neckline: average of left and right troughs (simplified — ideally slope-adjusted)
            double neckline = (lt.getPrice() + rt.getPrice()) / 2.0;
            double patternHeight = head.getPrice() - neckline;
            double target = neckline - patternHeight;
            boolean rsiDiv = rs.getRsi() < ls.getRsi();
            boolean macdDiv = rs.getMacdHistogram() < ls.getMacdHistogram();
            double confidence = 65 + (rsiDiv ? 10 : 0) + (macdDiv ? 10 : 0)
                    + (withinTolerance(ls.getPrice(), rs.getPrice(), 0.02) ? 5 : 0);

            // Check if right shoulder is complete and neckline broken
            EnrichedPivot afterRs = firstLowAfter(pivots, rs.getBarIndex());
            PatternStatus status;
            if (afterRs != null && afterRs.getPrice() < neckline) {
                status = PatternStatus.CONFIRMED;
            } else if (rt != null) {
                // Right shoulder formed, watching for neckline break
                status = PatternStatus.WATCHING;
            } else {
                // Only left shoulder + head confirmed — right shoulder BUILDING
                status = PatternStatus.BUILDING;
            }

            List<WaveContextHint> hints = hintsFor(PatternType.HEAD_AND_SHOULDERS, rs, neckline);

            results.add(PatternMatch.builder()
                    .type(PatternType.HEAD_AND_SHOULDERS)
                    .status(status)
                    .timeframe(timeframe)
                    .resistance(head.getPrice())
                    .neckline(neckline)
                    .target(target)
                    .partialTarget(neckline - patternHeight * 0.5)
                    .invalidation(head.getPrice() * 1.01)
                    .waitingFor(status == PatternStatus.WATCHING
                            ? "Close below neckline " + fmt(neckline) + " to confirm H&S"
                            : status == PatternStatus.BUILDING
                                    ? "Right shoulder forming near " + fmt(ls.getPrice())
                                    : null)
                    .pivotBarIndices(List.of(ls.getBarIndex(), lt.getBarIndex(), head.getBarIndex(), rt.getBarIndex(), rs.getBarIndex()))
                    .pivotPrices(List.of(ls.getPrice(), lt.getPrice(), head.getPrice(), rt.getPrice(), rs.getPrice()))
                    .pivotTimestamps(List.of(ls.getTimestamp(), lt.getTimestamp(), head.getTimestamp(), rt.getTimestamp(), rs.getTimestamp()))
                    .rsiDivergence(rsiDiv)
                    .macdDivergence(macdDiv)
                    .waveContextHints(hints)
                    .confirmationLevel(neckline)
                    .confidence(Math.min(confidence, 95))
                    .description("H&S: LS=" + fmt(ls.getPrice()) + " Head=" + fmt(head.getPrice())
                            + " RS=" + fmt(rs.getPrice()) + " neckline=" + fmt(neckline))
                    .build());
        }
        return results;
    }

    private List<PatternMatch> detectInvertedHeadAndShoulders(List<EnrichedPivot> pivots, String timeframe) {
        List<PatternMatch> results = new ArrayList<>();
        List<EnrichedPivot> lows = filterLows(pivots);

        for (int i = 0; i + 2 < lows.size(); i++) {
            EnrichedPivot ls = lows.get(i);
            EnrichedPivot head = lows.get(i + 1);
            EnrichedPivot rs = lows.get(i + 2);

            if (head.getPrice() >= ls.getPrice() || head.getPrice() >= rs.getPrice()) continue;
            if (!withinTolerance(ls.getPrice(), rs.getPrice(), EQUAL_LEVEL_TOLERANCE * 1.5)) continue;

            EnrichedPivot lt = firstHighAfter(pivots, ls.getBarIndex());
            EnrichedPivot rt = firstHighAfter(pivots, head.getBarIndex());
            if (lt == null || rt == null) continue;

            double neckline = (lt.getPrice() + rt.getPrice()) / 2.0;
            double patternHeight = neckline - head.getPrice();
            double target = neckline + patternHeight;
            boolean rsiDiv = rs.getRsi() > ls.getRsi();
            boolean macdDiv = rs.getMacdHistogram() > ls.getMacdHistogram();
            double confidence = 65 + (rsiDiv ? 10 : 0) + (macdDiv ? 10 : 0);

            EnrichedPivot afterRs = firstHighAfter(pivots, rs.getBarIndex());
            PatternStatus status = (afterRs != null && afterRs.getPrice() > neckline)
                    ? PatternStatus.CONFIRMED
                    : (rt != null ? PatternStatus.WATCHING : PatternStatus.BUILDING);

            List<WaveContextHint> hints = hintsFor(PatternType.INVERTED_HEAD_AND_SHOULDERS, rs, neckline);

            results.add(PatternMatch.builder()
                    .type(PatternType.INVERTED_HEAD_AND_SHOULDERS)
                    .status(status)
                    .timeframe(timeframe)
                    .support(head.getPrice())
                    .neckline(neckline)
                    .target(target)
                    .partialTarget(neckline + patternHeight * 0.5)
                    .invalidation(head.getPrice() * 0.97)
                    .waitingFor(status == PatternStatus.WATCHING
                            ? "Close above neckline " + fmt(neckline) + " to confirm inv. H&S"
                            : null)
                    .pivotBarIndices(List.of(ls.getBarIndex(), lt.getBarIndex(), head.getBarIndex(), rt.getBarIndex(), rs.getBarIndex()))
                    .pivotPrices(List.of(ls.getPrice(), lt.getPrice(), head.getPrice(), rt.getPrice(), rs.getPrice()))
                    .pivotTimestamps(List.of(ls.getTimestamp(), lt.getTimestamp(), head.getTimestamp(), rt.getTimestamp(), rs.getTimestamp()))
                    .rsiDivergence(rsiDiv)
                    .macdDivergence(macdDiv)
                    .waveContextHints(hints)
                    .confirmationLevel(neckline)
                    .confidence(Math.min(confidence, 95))
                    .description("Inv H&S: LS=" + fmt(ls.getPrice()) + " Head=" + fmt(head.getPrice())
                            + " RS=" + fmt(rs.getPrice()) + " neckline=" + fmt(neckline))
                    .build());
        }
        return results;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TRIANGLES
    // ══════════════════════════════════════════════════════════════════════════

    private List<PatternMatch> detectTriangles(List<EnrichedPivot> pivots, String timeframe) {
        List<PatternMatch> results = new ArrayList<>();
        int n = pivots.size();

        // Need at least 4 pivots (2 highs, 2 lows) for any triangle
        for (int start = 0; start < n - 3; start++) {
            // Collect a window of 4–8 pivots
            int end = Math.min(start + 8, n);
            List<EnrichedPivot> window = pivots.subList(start, end);
            List<EnrichedPivot> wHighs = filterHighs(window);
            List<EnrichedPivot> wLows = filterLows(window);

            if (wHighs.size() < 2 || wLows.size() < 2) continue;

            double firstHigh = wHighs.get(0).getPrice();
            double lastHigh = wHighs.get(wHighs.size() - 1).getPrice();
            double firstLow = wLows.get(0).getPrice();
            double lastLow = wLows.get(wLows.size() - 1).getPrice();

            boolean descendingHighs = lastHigh < firstHigh;
            boolean ascendingLows = lastLow > firstLow;
            boolean flatHighs = withinTolerance(firstHigh, lastHigh, 0.02);
            boolean flatLows = withinTolerance(firstLow, lastLow, 0.02);

            boolean bbSqueeze = window.stream().anyMatch(EnrichedPivot::isBollingerSqueeze);

            if (descendingHighs && ascendingLows) {
                // Symmetrical triangle
                double height = firstHigh - firstLow;
                PatternStatus status = isAtApex(wHighs, wLows) ? PatternStatus.WATCHING : PatternStatus.BUILDING;
                double triConfidence = (status == PatternStatus.WATCHING ? 72 : 48) + (bbSqueeze ? 15 : 0);

                List<WaveContextHint> hints = hintsFor(PatternType.SYMMETRICAL_TRIANGLE,
                        window.get(window.size() - 1), null);

                results.add(PatternMatch.builder()
                        .type(PatternType.SYMMETRICAL_TRIANGLE)
                        .status(status)
                        .timeframe(timeframe)
                        .support(lastLow)
                        .resistance(lastHigh)
                        .target(lastHigh + height)   // bullish breakout target
                        .invalidation(firstLow * 0.97)
                        .converging(true)
                        .pivotBarIndices(barIndices(window))
                        .pivotPrices(prices(window))
                        .pivotTimestamps(timestamps(window))
                        .bollingerSqueeze(bbSqueeze)
                        .waveContextHints(hints)
                        .confidence(triConfidence)
                        .waitingFor(status == PatternStatus.BUILDING
                                ? "More pivots needed to confirm symmetrical triangle"
                                : "Breakout above " + fmt(lastHigh) + " or below " + fmt(lastLow))
                        .description("Symmetrical Triangle: highs " + fmt(firstHigh) + "→" + fmt(lastHigh)
                                + " lows " + fmt(firstLow) + "→" + fmt(lastLow)
                                + (bbSqueeze ? " [BB SQUEEZE]" : ""))
                        .build());

            } else if (flatHighs && ascendingLows) {
                // Ascending triangle — bullish
                double height = firstHigh - firstLow;
                PatternStatus status = isAtApex(wHighs, wLows) ? PatternStatus.WATCHING : PatternStatus.BUILDING;
                double triConfidence = (status == PatternStatus.WATCHING ? 72 : 48) + (bbSqueeze ? 15 : 0);

                List<WaveContextHint> hints = hintsFor(PatternType.ASCENDING_TRIANGLE,
                        window.get(window.size() - 1), null);

                results.add(PatternMatch.builder()
                        .type(PatternType.ASCENDING_TRIANGLE)
                        .status(status)
                        .timeframe(timeframe)
                        .support(lastLow)
                        .resistance((firstHigh + lastHigh) / 2)
                        .target((firstHigh + lastHigh) / 2 + height)
                        .invalidation(firstLow * 0.97)
                        .converging(true)
                        .pivotBarIndices(barIndices(window))
                        .pivotPrices(prices(window))
                        .pivotTimestamps(timestamps(window))
                        .bollingerSqueeze(bbSqueeze)
                        .waveContextHints(hints)
                        .confidence(triConfidence)
                        .waitingFor(status == PatternStatus.BUILDING
                                ? "More touches of resistance needed"
                                : "Breakout above flat resistance " + fmt((firstHigh + lastHigh) / 2))
                        .description("Ascending Triangle: flat resistance ~" + fmt((firstHigh + lastHigh) / 2)
                                + " rising support " + fmt(firstLow) + "→" + fmt(lastLow)
                                + (bbSqueeze ? " [BB SQUEEZE]" : ""))
                        .build());

            } else if (descendingHighs && flatLows) {
                // Descending triangle — bearish
                double height = firstHigh - firstLow;
                PatternStatus status = isAtApex(wHighs, wLows) ? PatternStatus.WATCHING : PatternStatus.BUILDING;
                double triConfidence = (status == PatternStatus.WATCHING ? 72 : 48) + (bbSqueeze ? 15 : 0);

                List<WaveContextHint> hints = hintsFor(PatternType.DESCENDING_TRIANGLE,
                        window.get(window.size() - 1), null);

                results.add(PatternMatch.builder()
                        .type(PatternType.DESCENDING_TRIANGLE)
                        .status(status)
                        .timeframe(timeframe)
                        .support((firstLow + lastLow) / 2)
                        .resistance(firstHigh)
                        .target((firstLow + lastLow) / 2 - height)
                        .invalidation(firstHigh * 1.03)
                        .converging(true)
                        .pivotBarIndices(barIndices(window))
                        .pivotPrices(prices(window))
                        .pivotTimestamps(timestamps(window))
                        .bollingerSqueeze(bbSqueeze)
                        .waveContextHints(hints)
                        .confidence(triConfidence)
                        .waitingFor(status == PatternStatus.BUILDING
                                ? "More touches of support needed"
                                : "Breakdown below flat support " + fmt((firstLow + lastLow) / 2))
                        .description("Descending Triangle: falling resistance " + fmt(firstHigh) + "→" + fmt(lastHigh)
                                + " flat support ~" + fmt((firstLow + lastLow) / 2)
                                + (bbSqueeze ? " [BB SQUEEZE]" : ""))
                        .build());
            }
        }
        return results;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // WEDGES
    // ══════════════════════════════════════════════════════════════════════════

    private List<PatternMatch> detectWedges(List<EnrichedPivot> pivots, String timeframe) {
        List<PatternMatch> results = new ArrayList<>();
        int n = pivots.size();

        for (int start = 0; start < n - 3; start++) {
            int end = Math.min(start + 8, n);
            List<EnrichedPivot> window = pivots.subList(start, end);
            List<EnrichedPivot> wHighs = filterHighs(window);
            List<EnrichedPivot> wLows = filterLows(window);
            if (wHighs.size() < 2 || wLows.size() < 2) continue;

            double firstHigh = wHighs.get(0).getPrice();
            double lastHigh = wHighs.get(wHighs.size() - 1).getPrice();
            double firstLow = wLows.get(0).getPrice();
            double lastLow = wLows.get(wLows.size() - 1).getPrice();

            boolean highsRising = lastHigh > firstHigh;
            boolean lowsRising = lastLow > firstLow;
            boolean highsFalling = lastHigh < firstHigh;
            boolean lowsFalling = lastLow < firstLow;

            double highSlope = slopePct(firstHigh, lastHigh, wHighs.size());
            double lowSlope = slopePct(firstLow, lastLow, wLows.size());

            if (highsRising && lowsRising && lowSlope > highSlope) {
                // Rising wedge — lows rising faster than highs → converging upward → bearish
                EnrichedPivot lastHighPivot = wHighs.get(wHighs.size() - 1);
                boolean rsiDiv = wHighs.size() >= 2
                        && wHighs.get(wHighs.size() - 1).getRsi() < wHighs.get(wHighs.size() - 2).getRsi();
                boolean ewoDiv = wHighs.size() >= 2
                        && wHighs.get(wHighs.size() - 1).getEwo() < wHighs.get(wHighs.size() - 2).getEwo();
                boolean bbSqueeze = window.stream().anyMatch(EnrichedPivot::isBollingerSqueeze);
                double target = firstLow;
                double confirmationLvl = lastLow;

                double confidence = 60
                        + (rsiDiv   ? 15 : 0)
                        + (ewoDiv   ? 10 : 0)
                        + (bbSqueeze ? 10 : 0);

                List<WaveContextHint> hints = hintsFor(PatternType.RISING_WEDGE,
                        lastHighPivot, confirmationLvl);

                results.add(PatternMatch.builder()
                        .type(PatternType.RISING_WEDGE)
                        .status(isAtApex(wHighs, wLows) ? PatternStatus.WATCHING : PatternStatus.BUILDING)
                        .timeframe(timeframe)
                        .resistance(lastHigh)
                        .support(lastLow)
                        .target(target)
                        .invalidation(lastHigh * 1.02)
                        .converging(true)
                        .upperTrendlineSlope(highSlope)
                        .lowerTrendlineSlope(lowSlope)
                        .pivotBarIndices(barIndices(window))
                        .pivotPrices(prices(window))
                        .pivotTimestamps(timestamps(window))
                        .rsiDivergence(rsiDiv)
                        .ewoConfirmation(ewoDiv)
                        .bollingerSqueeze(bbSqueeze)
                        .waveContextHints(hints)
                        .confirmationLevel(confirmationLvl)
                        .confidence(confidence)
                        .description("Rising Wedge (bearish): highs " + fmt(firstHigh) + "→" + fmt(lastHigh)
                                + " lows " + fmt(firstLow) + "→" + fmt(lastLow)
                                + (bbSqueeze ? " [BB SQUEEZE]" : "")
                                + (ewoDiv ? " [EWO DIV]" : ""))
                        .build());

            } else if (highsFalling && lowsFalling && highSlope < lowSlope) {
                // Falling wedge — highs falling faster than lows → converging downward → bullish
                boolean rsiDiv = wLows.size() >= 2
                        && wLows.get(wLows.size() - 1).getRsi() > wLows.get(wLows.size() - 2).getRsi();
                // EWO divergence: EWO rising while price falling = bullish divergence
                boolean ewoDiv = wLows.size() >= 2
                        && wLows.get(wLows.size() - 1).getEwo() > wLows.get(wLows.size() - 2).getEwo();
                // BB squeeze at apex = imminent breakout
                boolean bbSqueeze = window.stream().anyMatch(EnrichedPivot::isBollingerSqueeze);
                double target = firstHigh;
                double confirmationLvl = lastHigh; // upper wedge line

                // Confidence: base 60, indicators only add — never subtract
                double confidence = 60
                        + (rsiDiv  ? 15 : 0)
                        + (ewoDiv  ? 10 : 0)
                        + (bbSqueeze ? 10 : 0);

                List<WaveContextHint> hints = hintsFor(PatternType.FALLING_WEDGE,
                        wLows.get(wLows.size() - 1), confirmationLvl);

                results.add(PatternMatch.builder()
                        .type(PatternType.FALLING_WEDGE)
                        .status(isAtApex(wHighs, wLows) ? PatternStatus.WATCHING : PatternStatus.BUILDING)
                        .timeframe(timeframe)
                        .support(lastLow)
                        .resistance(lastHigh)
                        .target(target)
                        .invalidation(lastLow * 0.97)
                        .converging(true)
                        .upperTrendlineSlope(highSlope)
                        .lowerTrendlineSlope(lowSlope)
                        .pivotBarIndices(barIndices(window))
                        .pivotPrices(prices(window))
                        .pivotTimestamps(timestamps(window))
                        .rsiDivergence(rsiDiv)
                        .ewoConfirmation(ewoDiv)
                        .bollingerSqueeze(bbSqueeze)
                        .waveContextHints(hints)
                        .confirmationLevel(confirmationLvl)
                        .confidence(confidence)
                        .description("Falling Wedge (bullish): highs " + fmt(firstHigh) + "→" + fmt(lastHigh)
                                + " lows " + fmt(firstLow) + "→" + fmt(lastLow)
                                + (bbSqueeze ? " [BB SQUEEZE]" : "")
                                + (ewoDiv ? " [EWO DIV]" : ""))
                        .build());
            }
        }
        return results;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FLAGS AND PENNANTS
    // ══════════════════════════════════════════════════════════════════════════

    private List<PatternMatch> detectFlags(List<EnrichedPivot> pivots, String timeframe) {
        List<PatternMatch> results = new ArrayList<>();

        for (int i = 1; i < pivots.size(); i++) {
            EnrichedPivot prev = pivots.get(i - 1);
            EnrichedPivot curr = pivots.get(i);

            // Pole: a sharp strong move between consecutive pivots (> 8% price move)
            double poleMove = Math.abs(curr.getPrice() - prev.getPrice()) / prev.getPrice();
            if (poleMove < 0.08) continue;

            boolean bullPole = curr.isHigh() && curr.getPrice() > prev.getPrice();
            boolean bearPole = curr.isLow() && curr.getPrice() < prev.getPrice();

            // Consolidation after pole: next 2–5 pivots
            List<EnrichedPivot> consol = pivots.subList(i + 1, Math.min(i + 6, pivots.size()));
            if (consol.size() < 2) {
                // BUILDING: pole found, no consolidation yet
                PatternType type = bullPole ? PatternType.BULL_FLAG : PatternType.BEAR_FLAG;
                double poleTop = bullPole ? curr.getPrice() : prev.getPrice();
                double poleBase = bullPole ? prev.getPrice() : curr.getPrice();
                double poleLength = poleTop - poleBase;
                results.add(PatternMatch.builder()
                        .type(type)
                        .status(PatternStatus.BUILDING)
                        .timeframe(timeframe)
                        .support(bullPole ? curr.getPrice() * 0.97 : null)
                        .resistance(bearPole ? curr.getPrice() * 1.03 : null)
                        .target(bullPole ? poleTop + poleLength : poleBase - poleLength)
                        .invalidation(bullPole ? poleBase : poleTop)
                        .pivotBarIndices(List.of(prev.getBarIndex(), curr.getBarIndex()))
                        .pivotPrices(List.of(prev.getPrice(), curr.getPrice()))
                        .pivotTimestamps(List.of(prev.getTimestamp(), curr.getTimestamp()))
                        .confidence(35)
                        .waitingFor("Consolidation / shallow pullback after " + (bullPole ? "bullish" : "bearish") + " pole")
                        .description((bullPole ? "Bull" : "Bear") + " Flag BUILDING — pole " + fmt(poleMove * 100) + "%")
                        .build());
                continue;
            }

            // Check consolidation: retracement should be < 50% of pole
            double maxConsol = consol.stream().mapToDouble(EnrichedPivot::getPrice).max().orElse(0);
            double minConsol = consol.stream().mapToDouble(EnrichedPivot::getPrice).min().orElse(0);
            double poleTop = bullPole ? curr.getPrice() : prev.getPrice();
            double poleBase = bullPole ? prev.getPrice() : curr.getPrice();
            double poleLength = Math.abs(poleTop - poleBase);
            double consolDepth = bullPole ? (poleTop - minConsol) / poleLength
                                          : (maxConsol - poleBase) / poleLength;
            if (consolDepth > 0.50 || consolDepth < 0.03) continue;

            List<EnrichedPivot> consolHighs = filterHighs(consol);
            List<EnrichedPivot> consolLows = filterLows(consol);
            boolean consolParallel = consolHighs.size() >= 2 && consolLows.size() >= 2
                    && Math.abs(slopePct(consolHighs.get(0).getPrice(), consolHighs.get(consolHighs.size() - 1).getPrice(), consolHighs.size())
                                - slopePct(consolLows.get(0).getPrice(), consolLows.get(consolLows.size() - 1).getPrice(), consolLows.size())) < 0.01;
            boolean consolConverging = consolHighs.size() >= 2 && consolLows.size() >= 2 && !consolParallel;

            PatternType type = bullPole
                    ? (consolConverging ? PatternType.BULL_PENNANT : PatternType.BULL_FLAG)
                    : (consolConverging ? PatternType.BEAR_PENNANT : PatternType.BEAR_FLAG);

            // Volume confirmation: volume should contract during consolidation vs pole
            boolean volumeContracting = false;
            if (!consol.isEmpty()) {
                double poleVol  = curr.getRelativeVolume();
                double consolAvgVol = consol.stream().mapToDouble(EnrichedPivot::getRelativeVolume).average().orElse(1.0);
                volumeContracting = consolAvgVol < poleVol * 0.7;
            }

            double flagConfidence = 65
                    + (volumeContracting ? 15 : 0);  // volume confirmation adds probability only

            EnrichedPivot lastConsolPivot = consol.isEmpty() ? curr : consol.get(consol.size() - 1);
            List<WaveContextHint> hints = hintsFor(type, lastConsolPivot, null);

            results.add(PatternMatch.builder()
                    .type(type)
                    .status(PatternStatus.WATCHING)
                    .timeframe(timeframe)
                    .support(minConsol)
                    .resistance(maxConsol)
                    .target(bullPole ? poleTop + poleLength : poleBase - poleLength)
                    .invalidation(bullPole ? poleBase : poleTop)
                    .converging(consolConverging)
                    .parallel(consolParallel)
                    .pivotBarIndices(barIndices(consol))
                    .pivotPrices(prices(consol))
                    .pivotTimestamps(timestamps(consol))
                    .volumeConfirmation(volumeContracting)
                    .waveContextHints(hints)
                    .confidence(flagConfidence)
                    .waitingFor("Breakout from consolidation zone "
                            + fmt(minConsol) + "–" + fmt(maxConsol))
                    .description(type.name() + ": pole " + fmt(poleLength) + " points, consol depth "
                            + fmt(consolDepth * 100) + "%"
                            + (volumeContracting ? " [VOL CONTRACTION]" : ""))
                    .build());
        }
        return results;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CHANNELS
    // ══════════════════════════════════════════════════════════════════════════

    private List<PatternMatch> detectChannels(List<EnrichedPivot> pivots, String timeframe) {
        List<PatternMatch> results = new ArrayList<>();

        for (int start = 0; start + 3 < pivots.size(); start++) {
            List<EnrichedPivot> window = pivots.subList(start, Math.min(start + 8, pivots.size()));
            List<EnrichedPivot> wHighs = filterHighs(window);
            List<EnrichedPivot> wLows = filterLows(window);
            if (wHighs.size() < 2 || wLows.size() < 2) continue;

            double highSlope = slopePct(wHighs.get(0).getPrice(), wHighs.get(wHighs.size() - 1).getPrice(), wHighs.size());
            double lowSlope  = slopePct(wLows.get(0).getPrice(), wLows.get(wLows.size() - 1).getPrice(), wLows.size());

            // Parallel: slopes within 20% of each other
            boolean parallel = Math.abs(highSlope - lowSlope) < Math.abs(highSlope) * 0.20 + 0.001;
            if (!parallel) continue;

            if (highSlope > 0.001 && lowSlope > 0.001) {
                // Ascending channel
                double lastHigh = wHighs.get(wHighs.size() - 1).getPrice();
                double lastLow  = wLows.get(wLows.size() - 1).getPrice();
                double channelHeight = lastHigh - lastLow;

                List<WaveContextHint> hints = hintsFor(PatternType.ASCENDING_CHANNEL,
                        window.get(window.size() - 1), null);

                results.add(PatternMatch.builder()
                        .type(PatternType.ASCENDING_CHANNEL)
                        .status(PatternStatus.WATCHING)
                        .timeframe(timeframe)
                        .support(lastLow)
                        .resistance(lastHigh)
                        .target(lastHigh + channelHeight)
                        .invalidation(wLows.get(0).getPrice() * 0.97)
                        .parallel(true)
                        .upperTrendlineSlope(highSlope)
                        .lowerTrendlineSlope(lowSlope)
                        .pivotBarIndices(barIndices(window))
                        .pivotPrices(prices(window))
                        .pivotTimestamps(timestamps(window))
                        .waveContextHints(hints)
                        .confidence(60)
                        .description("Ascending Channel: support " + fmt(lastLow) + " resistance " + fmt(lastHigh))
                        .build());

            } else if (highSlope < -0.001 && lowSlope < -0.001) {
                // Descending channel
                double lastHigh = wHighs.get(wHighs.size() - 1).getPrice();
                double lastLow  = wLows.get(wLows.size() - 1).getPrice();
                double channelHeight = lastHigh - lastLow;

                List<WaveContextHint> hints = hintsFor(PatternType.DESCENDING_CHANNEL,
                        window.get(window.size() - 1), lastHigh);

                results.add(PatternMatch.builder()
                        .type(PatternType.DESCENDING_CHANNEL)
                        .status(PatternStatus.WATCHING)
                        .timeframe(timeframe)
                        .support(lastLow)
                        .resistance(lastHigh)
                        .target(lastLow - channelHeight)
                        .invalidation(wHighs.get(0).getPrice() * 1.03)
                        .parallel(true)
                        .upperTrendlineSlope(highSlope)
                        .lowerTrendlineSlope(lowSlope)
                        .pivotBarIndices(barIndices(window))
                        .pivotPrices(prices(window))
                        .pivotTimestamps(timestamps(window))
                        .waveContextHints(hints)
                        .confirmationLevel(lastHigh)
                        .confidence(60)
                        .description("Descending Channel: support " + fmt(lastLow) + " resistance " + fmt(lastHigh))
                        .build());
            }
        }
        return results;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ELLIOTT CORRECTIVE PATTERNS (Zigzag, Flat, Expanded Flat)
    // Detected as named patterns so they carry WaveContextHints into ScenarioBuilder
    // ══════════════════════════════════════════════════════════════════════════

    private List<PatternMatch> detectCorrectivePatterns(List<EnrichedPivot> pivots, String timeframe) {
        List<PatternMatch> results = new ArrayList<>();

        // Only scan recent pivots — corrective patterns are current-context
        int startFrom = Math.max(0, pivots.size() - 24);

        for (int i = startFrom; i < pivots.size() - 1; i++) {
            EnrichedPivot p = pivots.get(i);

            // Downward correction (A down, B up, C down) — starting from a HIGH
            if (p.isHigh()) {
                PatternMatch corr = tryCorrectiveABC(pivots, i, timeframe, false);
                if (corr != null) results.add(corr);
            }

            // Upward correction (A up, B down, C up) — starting from a LOW
            if (p.isLow()) {
                PatternMatch corr = tryCorrectiveABC(pivots, i, timeframe, true);
                if (corr != null) results.add(corr);
            }
        }
        return results;
    }

    private PatternMatch tryCorrectiveABC(List<EnrichedPivot> pivots, int startIdx,
                                           String timeframe, boolean upward) {
        EnrichedPivot wAStart = pivots.get(startIdx);

        // Wave A: first leg
        EnrichedPivot wAEnd = upward
                ? firstHighAfter(pivots, wAStart.getBarIndex())
                : firstLowAfter(pivots, wAStart.getBarIndex());
        if (wAEnd == null) return null;

        double aLength = Math.abs(wAEnd.getPrice() - wAStart.getPrice());
        if (aLength <= 0) return null;

        // Wave B: counter-move
        EnrichedPivot wBEnd = upward
                ? firstLowAfter(pivots, wAEnd.getBarIndex())
                : firstHighAfter(pivots, wAEnd.getBarIndex());
        if (wBEnd == null) return null;

        double bRetrace = Math.abs(wBEnd.getPrice() - wAEnd.getPrice()) / aLength;
        if (bRetrace >= 1.0) return null; // B cannot exceed A origin

        // Classify: zigzag (<78.6%) vs flat (≥90%) vs expanded flat (B > A origin)
        PatternType type;
        double bRetraceVsAStart = Math.abs(wBEnd.getPrice() - wAStart.getPrice()) / aLength;

        if (bRetraceVsAStart > 1.0) {
            // B went past A's origin = expanded flat
            type = PatternType.ELLIOTT_EXPANDED_FLAT;
        } else if (bRetrace <= 0.786) {
            type = PatternType.ELLIOTT_ZIGZAG;
        } else if (bRetrace >= 0.90) {
            type = PatternType.ELLIOTT_FLAT;
        } else {
            return null; // Ambiguous zone — skip
        }

        // Wave C: final leg
        EnrichedPivot wCEnd = upward
                ? firstHighAfter(pivots, wBEnd.getBarIndex())
                : firstLowAfter(pivots, wBEnd.getBarIndex());

        // RSI/EWO divergence at WC vs WA (bearish divergence in upward correction, bullish in downward)
        boolean divergence = false;
        PatternStatus status;
        double confidence = 55;

        if (wCEnd == null) {
            status = PatternStatus.WATCHING;
            confidence = 45;
        } else {
            double cLength = Math.abs(wCEnd.getPrice() - wBEnd.getPrice());
            double cToA = cLength / aLength;

            // C = 61.8%–161.8% of A = valid range
            if (cToA < 0.3 || cToA > 2.0) return null;

            // Divergence at WC: for downward correction, WC RSI higher than WA RSI = bullish divergence
            if (!upward) {
                divergence = wCEnd.getRsi() > wAEnd.getRsi()
                        || wCEnd.getEwo() > wAEnd.getEwo();
            } else {
                divergence = wCEnd.getRsi() < wAEnd.getRsi()
                        || wCEnd.getEwo() < wAEnd.getEwo();
            }

            status = PatternStatus.WATCHING; // completed ABC but waiting for reversal confirmation
            confidence = 60
                    + (divergence ? 15 : 0)
                    + (cToA >= 0.618 && cToA <= 1.618 ? 10 : 0);  // Fib range confirmation adds probability
        }

        // MACD near zero at WB = W4 sub-structure signature
        boolean macdNearZero = wBEnd.isMacdNearZero();
        if (macdNearZero) confidence += 8; // W4 sub-wave context strengthened

        List<EnrichedPivot> patternPivots = wCEnd != null
                ? List.of(wAStart, wAEnd, wBEnd, wCEnd)
                : List.of(wAStart, wAEnd, wBEnd);

        List<Integer> barIndices = patternPivots.stream().map(EnrichedPivot::getBarIndex).toList();
        List<Double> priceList = patternPivots.stream().map(EnrichedPivot::getPrice).toList();
        List<java.time.Instant> timestamps = patternPivots.stream().map(EnrichedPivot::getTimestamp).toList();

        Double confirmLevel = wCEnd != null
                ? wAEnd.getPrice()
                : null;

        List<WaveContextHint> hints = hintsFor(type, wBEnd, confirmLevel);

        return PatternMatch.builder()
                .type(type)
                .status(status)
                .timeframe(timeframe)
                .support(!upward ? (wCEnd != null ? wCEnd.getPrice() : wAEnd.getPrice()) : null)
                .resistance(upward ? (wCEnd != null ? wCEnd.getPrice() : wAEnd.getPrice()) : null)
                .target(wCEnd != null ? wCEnd.getPrice() : null)
                .invalidation(upward ? wAStart.getPrice() * 0.97 : wAStart.getPrice() * 1.03)
                .waitingFor(wCEnd == null ? "Wave C forming — watch for final leg " + (upward ? "up" : "down") : null)
                .pivotBarIndices(barIndices)
                .pivotPrices(priceList)
                .pivotTimestamps(timestamps)
                .rsiDivergence(divergence)
                .ewoConfirmation(divergence)
                .waveContextHints(hints)
                .confirmationLevel(confirmLevel)
                .confidence(Math.min(confidence, 90))
                .description(type.name() + " " + (upward ? "up" : "down") + ": A=" + fmt(wAEnd.getPrice())
                        + " B=" + fmt(wBEnd.getPrice())
                        + (wCEnd != null ? " C=" + fmt(wCEnd.getPrice()) : " [C forming]")
                        + (divergence ? " [DIV]" : "")
                        + (macdNearZero ? " [MACD≈0 W4]" : ""))
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private List<EnrichedPivot> filterHighs(List<EnrichedPivot> pivots) {
        return pivots.stream().filter(EnrichedPivot::isHigh).toList();
    }

    private List<EnrichedPivot> filterLows(List<EnrichedPivot> pivots) {
        return pivots.stream().filter(EnrichedPivot::isLow).toList();
    }

    private EnrichedPivot firstHighAfter(List<EnrichedPivot> pivots, int afterBarIndex) {
        return pivots.stream()
                .filter(p -> p.isHigh() && p.getBarIndex() > afterBarIndex)
                .findFirst().orElse(null);
    }

    private EnrichedPivot firstLowAfter(List<EnrichedPivot> pivots, int afterBarIndex) {
        return pivots.stream()
                .filter(p -> p.isLow() && p.getBarIndex() > afterBarIndex)
                .findFirst().orElse(null);
    }

    private List<EnrichedPivot> lowsAfter(List<EnrichedPivot> pivots, int afterBarIndex) {
        return pivots.stream()
                .filter(p -> p.isLow() && p.getBarIndex() > afterBarIndex)
                .toList();
    }

    private List<EnrichedPivot> highsAfter(List<EnrichedPivot> pivots, int afterBarIndex) {
        return pivots.stream()
                .filter(p -> p.isHigh() && p.getBarIndex() > afterBarIndex)
                .toList();
    }

    private boolean withinTolerance(double a, double b, double tolerance) {
        return Math.abs(a - b) / Math.max(a, b) <= tolerance;
    }

    private boolean isAtApex(List<EnrichedPivot> highs, List<EnrichedPivot> lows) {
        if (highs.size() < 3 || lows.size() < 3) return false;
        double highRange  = highs.get(0).getPrice() - highs.get(highs.size() - 1).getPrice();
        double lowRange   = lows.get(lows.size() - 1).getPrice() - lows.get(0).getPrice();
        double currentGap = highs.get(highs.size() - 1).getPrice() - lows.get(lows.size() - 1).getPrice();
        double startGap   = highs.get(0).getPrice() - lows.get(0).getPrice();
        // At apex if current gap is < 40% of starting gap
        return currentGap < startGap * 0.40;
    }

    private double slopePct(double first, double last, int periods) {
        return periods <= 1 ? 0 : (last - first) / first / periods;
    }

    private double avg(double... values) {
        double sum = 0;
        for (double v : values) sum += v;
        return sum / values.length;
    }

    private String fmt(double v) {
        return String.format("%.2f", v);
    }

    private List<Integer> barIndices(List<EnrichedPivot> pivots) {
        return pivots.stream().map(EnrichedPivot::getBarIndex).toList();
    }

    private List<Double> prices(List<EnrichedPivot> pivots) {
        return pivots.stream().map(EnrichedPivot::getPrice).toList();
    }

    private List<java.time.Instant> timestamps(List<EnrichedPivot> pivots) {
        return pivots.stream().map(EnrichedPivot::getTimestamp).toList();
    }
}
