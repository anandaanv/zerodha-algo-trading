package com.dtech.ta.elliott;

/**
 * Lifecycle status of a detected pattern.
 *
 * BUILDING  — First key pivot has formed; pattern is starting but not yet confirmed.
 *             E.g., first low of a double bottom is in, watching for bounce + retest.
 *
 * WATCHING  — All structural pivots exist; waiting for the breakout/confirmation trigger.
 *             E.g., both lows of double bottom are in, watching for close above neckline.
 *
 * CONFIRMED — Breakout/confirmation candle has closed. Pattern is actionable.
 *             Entry signal may be issued immediately or on a pullback retest.
 */
public enum PatternStatus {
    BUILDING,
    WATCHING,
    CONFIRMED
}
