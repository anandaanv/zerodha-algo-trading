package com.dtech.aitrader.v2.narrative.pivot;

/**
 * Immutable parameters controlling pivot detection sensitivity and validation.
 *
 * Ported from ZigZagParams but series-general: operates on any numeric 1D array
 * (MACD, RSI, indicator derivatives, etc.) without OHLC bars or time information.
 */
public record SignificanceParams(
    /** Wilder ATR baseline window (default 14). Higher = smoother volatility estimate. */
    int atrLength,
    /** Multiplier on ATR for reversal threshold (default 2.5). Higher = fewer pivots. */
    double atrMult,
    /** Percentage floor on value for threshold (default 0.02 = 2%). Protects against values near zero. */
    double pctMin,
    /** Hysteresis factor; reversal must exceed forward threshold × this (default 0.7). */
    double hysteresis,
    /** Minimum bars between consecutive pivots (default 3). Prevents clustering. */
    int minBarsBetweenPivots,
    /** If true, use dynamic relative volatility; if false, use fixed pctMin (default false for Phase 0). */
    boolean dynamicPctEnabled,
    /** Volatility multiplier for dynamic mode (default 1.5). Only used if dynamicPctEnabled=true. */
    double volMult,
    /** EMA window for relative volatility (default 20). Only used if dynamicPctEnabled=true. */
    int rvolWindow
) {
    /**
     * Validates parameter invariants.
     */
    public SignificanceParams {
        if (atrLength <= 0) {
            throw new IllegalArgumentException("atrLength must be positive, got " + atrLength);
        }
        if (atrMult <= 0) {
            throw new IllegalArgumentException("atrMult must be positive, got " + atrMult);
        }
        if (pctMin < 0 || pctMin > 1) {
            throw new IllegalArgumentException("pctMin must be in [0,1], got " + pctMin);
        }
        if (hysteresis <= 0 || hysteresis > 1) {
            throw new IllegalArgumentException("hysteresis must be in (0,1], got " + hysteresis);
        }
        if (minBarsBetweenPivots < 1) {
            throw new IllegalArgumentException("minBarsBetweenPivots must be >= 1, got " + minBarsBetweenPivots);
        }
        if (volMult <= 0) {
            throw new IllegalArgumentException("volMult must be positive, got " + volMult);
        }
        if (rvolWindow <= 0) {
            throw new IllegalArgumentException("rvolWindow must be positive, got " + rvolWindow);
        }
    }

    /**
     * Creates a new SignificanceParams with all default values.
     * Defaults are identical to ZigZagService parameters for consistency.
     *
     * @return SignificanceParams with: atrLength=14, atrMult=2.5, pctMin=0.02, hysteresis=0.7,
     *         minBarsBetweenPivots=3, dynamicPctEnabled=false, volMult=1.5, rvolWindow=20
     */
    public static SignificanceParams ofDefaults() {
        return new SignificanceParams(
            14,      // atrLength
            2.5,     // atrMult
            0.02,    // pctMin
            0.7,     // hysteresis
            3,       // minBarsBetweenPivots
            false,   // dynamicPctEnabled
            1.5,     // volMult
            20       // rvolWindow
        );
    }
}
