package com.dtech.aitrader.v2.rules;

import java.time.Instant;

/**
 * Trader annotation lifted from the scan-context — free-text intent overlay with a weight that
 * drives Rule 0.35 / Rule 0.7 prior bumps in the EW family.
 *
 * <p>Example (RELIANCE blessed reference {@code cde6bbc9}): {@code text="on weekly appears to be
 * in wave 4C; 2 of C or 4 of C; right value is lower", weight=3}.
 *
 * <p>The {@code priceLevel} field is optional — annotations may carry a specific level the trader
 * called out (e.g. "1290 is invalidation"), or simply be a structural narrative without a price.
 */
public record AnnotationEntry(
        String text,
        int weight,
        Instant timestamp,
        Double priceLevel
) {
    public AnnotationEntry(String text, int weight, Instant timestamp) {
        this(text, weight, timestamp, null);
    }
}
