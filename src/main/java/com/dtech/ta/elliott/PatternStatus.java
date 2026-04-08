package com.dtech.ta.elliott;

/**
 * Lifecycle status of a detected pattern or wave count.
 *
 * BUILDING    — First key pivot has formed; pattern is starting but not yet complete.
 *               E.g., first low of a double bottom is in, watching for bounce + retest.
 *
 * WATCHING    — All structural pivots exist; waiting for the breakout/confirmation trigger.
 *               E.g., both lows of double bottom are in, watching for close above neckline.
 *
 * PARTIAL     — Wave count in progress; key wave not yet complete (e.g. C-wave forming, W5 not started).
 *               Used for wave-count-derived patterns where the final leg is still unfolding.
 *
 * CONFIRMED   — Breakout/confirmation candle has closed. Pattern is actionable.
 *               Entry signal may be issued immediately or on a pullback retest.
 *
 * INVALIDATED — Pattern has been invalidated by price action (e.g. W4 overlap, neckline break in wrong direction).
 *               Kept in history but excluded from active analysis.
 */
public enum PatternStatus {
    BUILDING,
    WATCHING,
    PARTIAL,
    CONFIRMED,
    INVALIDATED
}
