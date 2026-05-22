package com.dtech.aitrader.v2.narrative.pivot;

/**
 * Immutable record representing a single detected pivot in a numeric series.
 *
 * A pivot is a point where the series changes direction (from increasing to decreasing or vice versa),
 * with significance measured relative to the series's own volatility.
 */
public record SeriesPivot(
    /** Index into the input series where this pivot is located. */
    int idx,
    /** The series value at this pivot. */
    double value,
    /** The type of pivot: PEAK (local maximum) or TROUGH (local minimum). */
    PivotKind kind,
    /** Normalized significance score (0..1), computed as clamp01(prominence / (atrMult * atr[idx])). */
    double significance,
    /** Mean Absolute Difference (volatility proxy) at the pivot bar, used downstream for beat/confirmation logic. */
    double atrAtPivot
) {
    /**
     * Validates that the record invariants hold.
     */
    public SeriesPivot {
        if (idx < 0) {
            throw new IllegalArgumentException("idx must be non-negative, got " + idx);
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("value must be finite, got " + value);
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        if (significance < 0.0 || significance > 1.0) {
            throw new IllegalArgumentException("significance must be in [0,1], got " + significance);
        }
        if (!Double.isFinite(atrAtPivot) || atrAtPivot < 0) {
            throw new IllegalArgumentException("atrAtPivot must be non-negative and finite, got " + atrAtPivot);
        }
    }
}
