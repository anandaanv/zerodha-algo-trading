package com.dtech.ta.patterns.classic;

import java.time.Instant;
import java.util.List;

/**
 * Bullish Volatility Contraction Pattern: a 5-point base with twin peaks at similar level and tightening pullbacks.
 * Pivots: A (HIGH), B (LOW), C (HIGH near A), D (LOW higher than B), E (current close).
 * Reference: docs/spec-vcp-pattern-port.md
 */
public record BullishVcpPattern(
    Direction direction,
    List<PivotPoint> pivots,
    int eBarIndex,
    double ePrice,
    int startBarIndex,
    int endBarIndex,
    Instant startTime,
    Instant endTime,
    double avgBarLength
) implements ClassicPattern {

    public BullishVcpPattern {
        if (direction != Direction.BULLISH) {
            throw new IllegalArgumentException("Bullish VCP pattern must have BULLISH direction");
        }
        if (pivots.size() != 4) {
            throw new IllegalArgumentException("Bullish VCP pattern must have exactly 4 pivots (A, B, C, D)");
        }
    }
}
