package com.dtech.ta.elliott;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;

/**
 * A detected or building chart pattern, derived from ZigZag pivot sequences.
 *
 * Both BUILDING patterns (opportunity to watch) and CONFIRMED patterns (entry signal)
 * are returned so the upstream AI can track setups as they develop.
 */
@Data
@Builder
public class PatternMatch {

    private PatternType type;
    private PatternStatus status;
    private String timeframe;

    // ── Key price levels ──────────────────────────────────────────────────────
    /** Structural support — the level pattern must hold above to remain valid */
    private Double support;

    /** Structural resistance — the level price must break to confirm the pattern */
    private Double resistance;

    /** The neckline (for H&S, Double Top/Bottom, etc.) — break = confirmation */
    private Double neckline;

    /** Measured target: neckline + pattern height projection */
    private Double target;

    /** Conservative first target (e.g., 50% of full measured move) */
    private Double partialTarget;

    /** Price level that, if broken, invalidates this pattern entirely */
    private Double invalidation;

    // ── Pivot references ──────────────────────────────────────────────────────
    /** Bar indices of pivots defining this pattern (into the enriched pivot list) */
    private List<Integer> pivotBarIndices;

    /** Prices of the defining pivots (same order as pivotBarIndices) */
    private List<Double> pivotPrices;

    /** Timestamps of the defining pivots */
    private List<Instant> pivotTimestamps;

    // ── BUILDING pattern context ──────────────────────────────────────────────
    /** Human-readable description of what we're waiting for next */
    private String waitingFor;

    /**
     * The price level to watch for the next key pivot.
     * E.g., for a building Double Bottom: the first low price (watch for retest).
     */
    private Double watchLevel;

    /** Tolerance for watchLevel as a fraction (e.g., 0.03 = ±3%) */
    @Builder.Default
    private double watchTolerance = 0.03;

    // ── Indicator confluence ──────────────────────────────────────────────────
    private boolean rsiDivergence;
    private boolean macdDivergence;
    private boolean ewoConfirmation;
    private boolean volumeConfirmation;   // volume expanding on breakout
    private boolean bollingerSqueeze;     // squeeze preceding a breakout pattern

    /**
     * Wave position context inferred from this pattern.
     * Multiple hints possible (e.g., falling wedge could mean W5 of impulse OR end of Wave A).
     * DESIGN: hints only boost probability — they never eliminate competing wave counts.
     */
    @Builder.Default
    private List<WaveContextHint> waveContextHints = new java.util.ArrayList<>();

    /** Price level that confirms this pattern's wave context interpretation */
    private Double confirmationLevel;

    // ── Confidence & description ──────────────────────────────────────────────
    /** Score 0–100 reflecting structural quality + indicator confluence */
    private double confidence;

    /** One-line description for use in AI prompt context */
    private String description;

    // ── Trendline context (for channel / wedge / triangle patterns) ───────────
    /** Slope of upper trendline (bars as x-axis) — negative = descending resistance */
    private Double upperTrendlineSlope;

    /** Slope of lower trendline (bars as x-axis) — positive = ascending support */
    private Double lowerTrendlineSlope;

    /** True if the two trendlines are converging (triangle / wedge) */
    private boolean converging;

    /** True if the two trendlines are parallel (channel / flag) */
    private boolean parallel;

    public boolean isBullish() {
        return switch (type) {
            case DOUBLE_BOTTOM, TRIPLE_BOTTOM, INVERTED_HEAD_AND_SHOULDERS,
                 CUP_AND_HANDLE, ROUNDING_BOTTOM, ASCENDING_TRIANGLE,
                 BULL_FLAG, BULL_PENNANT, FALLING_WEDGE, BROADENING_BOTTOM,
                 LEADING_DIAGONAL -> true;
            default -> false;
        };
    }

    public boolean isBearish() {
        return switch (type) {
            case DOUBLE_TOP, TRIPLE_TOP, HEAD_AND_SHOULDERS,
                 DESCENDING_TRIANGLE, BEAR_FLAG, BEAR_PENNANT,
                 RISING_WEDGE, BROADENING_TOP -> true;
            default -> false;
        };
    }
}
