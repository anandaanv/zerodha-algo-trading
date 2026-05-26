package com.dtech.aitrader.v2.rules.ew.signature;

/**
 * A single price level contributed to the engine's LEVEL-MAP by a live hypothesis. Per owner's
 * reframe ({@code 159ba913}) the engine's output is the level-map of (watch, invalidation) per
 * live hypothesis — NOT a confident single verdict.
 *
 * @param price   the level itself (in symbol's price units).
 * @param label   short human-readable name, e.g. {@code "C target"}, {@code "above A_start
 *                invalidates"}, {@code "1290 break confirms"}.
 * @param basis   explanation of how the level was derived from structural facts (e.g.
 *                {@code "B_end - |A magnitude| (zigzag C=A projection)"}).
 */
public record PriceLevel(double price, String label, String basis) {
}
