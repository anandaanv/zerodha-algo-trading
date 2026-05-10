package com.dtech.ta.patterns.classic;

import java.time.Instant;
import java.util.List;

/**
 * Bearish Volatility Contraction Pattern: a 5-point base with twin troughs at similar level and tightening rallies.
 * Pivots: A (LOW), B (HIGH), C (LOW near A), D (HIGH lower than B), E (current close).
 * Reference: docs/spec-vcp-pattern-port.md
 */
public record BearishVcpPattern(
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

    public BearishVcpPattern {
        if (direction != Direction.BEARISH) {
            throw new IllegalArgumentException("Bearish VCP pattern must have BEARISH direction");
        }
        if (pivots.size() != 4) {
            throw new IllegalArgumentException("Bearish VCP pattern must have exactly 4 pivots (A, B, C, D)");
        }
    }
}
