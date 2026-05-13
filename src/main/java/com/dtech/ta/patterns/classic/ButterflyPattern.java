package com.dtech.ta.patterns.classic;

import java.time.Instant;
import java.util.List;

/**
 * Butterfly harmonic pattern: 5 pivots forming a Fibonacci-based harmonic structure.
 * Pivots: X (LOW/HIGH), A (HIGH/LOW), B (LOW/HIGH), C (HIGH/LOW), D (LOW/HIGH, current).
 * Fib ratios: b_retrace == 0.786, c_retrace in [0.382, 0.886].
 * Reference: docs/spec-classic-patterns-port.md (Butterfly section)
 */
public record ButterflyPattern(
    Direction direction,
    ButterflyKind kind,
    List<PivotPoint> pivots,
    double bRetrace,
    double cRetrace,
    double terminalPoint,
    int startBarIndex,
    int endBarIndex,
    Instant startTime,
    Instant endTime,
    double avgBarLength
) implements ClassicPattern {

    /**
     * Butterfly pattern kind classification.
     */
    public enum ButterflyKind {
        REGULAR,
        PERFECT
    }

    public ButterflyPattern {
        if (direction == null) {
            throw new IllegalArgumentException("Butterfly pattern must have a non-null direction");
        }
        if (kind == null) {
            throw new IllegalArgumentException("Butterfly pattern must have a non-null kind");
        }
        if (pivots == null || pivots.size() != 5) {
            throw new IllegalArgumentException("Butterfly pattern must have exactly 5 pivots (X, A, B, C, D)");
        }
    }
}
