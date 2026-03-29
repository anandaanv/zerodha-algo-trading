package com.dtech.ta.elliott;

/**
 * All supported chart pattern types.
 * Each pattern can be in BUILDING, WATCHING, or COMPLETE status.
 */
public enum PatternType {

    // ── Reversal patterns ─────────────────────────────────────────────────────
    DOUBLE_BOTTOM,                // W-shape, bullish
    DOUBLE_TOP,                   // M-shape, bearish
    TRIPLE_BOTTOM,                // WWW-shape, bullish
    TRIPLE_TOP,                   // MMM-shape, bearish
    HEAD_AND_SHOULDERS,           // Left shoulder, head, right shoulder — bearish
    INVERTED_HEAD_AND_SHOULDERS,  // Inverse H&S — bullish
    CUP_AND_HANDLE,               // Rounded bottom + small flag — bullish
    ROUNDING_BOTTOM,              // Gradual curve, bullish

    // ── Continuation / bilateral patterns ────────────────────────────────────
    ASCENDING_TRIANGLE,           // Flat resistance, rising support — bullish
    DESCENDING_TRIANGLE,          // Flat support, falling resistance — bearish
    SYMMETRICAL_TRIANGLE,         // Converging trendlines — bilateral
    ASCENDING_CHANNEL,            // Parallel rising trendlines — bullish continuation
    DESCENDING_CHANNEL,           // Parallel falling trendlines — bearish continuation
    HORIZONTAL_CHANNEL,           // Flat range between support and resistance

    // ── Continuation flags ────────────────────────────────────────────────────
    BULL_FLAG,                    // Strong up move then shallow pullback channel
    BEAR_FLAG,                    // Strong down move then shallow bounce channel
    BULL_PENNANT,                 // Strong up move then small symmetrical triangle
    BEAR_PENNANT,                 // Strong down move then small symmetrical triangle

    // ── Wedge patterns ────────────────────────────────────────────────────────
    RISING_WEDGE,                 // Converging trendlines both rising — bearish
    FALLING_WEDGE,                // Converging trendlines both falling — bullish

    // ── Broadening patterns ───────────────────────────────────────────────────
    BROADENING_TOP,               // Expanding trendlines, bearish
    BROADENING_BOTTOM,            // Expanding trendlines, bullish

    // ── Elliott corrective structures (detected as named patterns) ────────────
    ELLIOTT_ZIGZAG,           // ABC: sharp A (5-wave), shallow B (<78.6%), sharp C (5-wave)
    ELLIOTT_FLAT,             // ABC: B retraces ≥90% of A, C near A end
    ELLIOTT_EXPANDED_FLAT,    // ABC: B exceeds A origin (>100%), C extends beyond A end

    // ── Diagonal patterns ─────────────────────────────────────────────────────
    LEADING_DIAGONAL,         // 5-wave converging with wave-4 overlap — appears at Wave 1 or Wave A start
    ENDING_DIAGONAL           // 5-wave converging terminal structure — appears at Wave 5 or Wave C, often truncated
}
