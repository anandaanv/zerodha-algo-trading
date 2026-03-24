package com.dtech.ta.elliott;

/**
 * Elliott Wave labels for both impulse and corrective waves.
 */
public enum WaveLabel {

    // ── Impulse wave numbers ───────────────────────────────────────────────────
    W1,  // First impulse wave
    W2,  // First correction
    W3,  // Strongest impulse wave (longest/steepest)
    W4,  // Second correction (must not overlap W1 territory)
    W5,  // Final impulse (often with MACD divergence)

    // ── Corrective wave letters ────────────────────────────────────────────────
    WA,  // First corrective wave (always 3 sub-waves internally)
    WB,  // Counter-correction within the corrective sequence
    WC,  // Completing correction (always 5 sub-waves for zigzag, 3 for flat)
    WD,  // Triangle wave D (4th swing)
    WE,  // Triangle wave E (5th swing — final, often near trendline apex)

    // ── Double/triple combination connector ────────────────────────────────────
    WX,  // Connector X wave linking two corrective patterns (WXY)
    WY,  // Second corrective pattern in double combination
    WZ,  // Third corrective pattern in triple combination

    // ── Unknown / not yet labeled ─────────────────────────────────────────────
    UNKNOWN
}
