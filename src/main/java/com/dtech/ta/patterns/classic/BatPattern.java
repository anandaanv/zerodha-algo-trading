package com.dtech.ta.patterns.classic;

import java.time.Instant;
import java.util.List;

/**
 * BAT harmonic pattern: 5 pivots forming a Fibonacci-based harmonic structure.
 * Pivots: X (LOW/HIGH), A (HIGH/LOW), B (LOW/HIGH), C (HIGH/LOW), D (LOW/HIGH, current).
 * Fib ratios: b_retrace in {0.382, 0.5}, c_retrace in [0.382, 0.886].
 * Reference: docs/spec-classic-patterns-port.md (BAT section)
 */
public record BatPattern(
    Direction direction,
    BatKind kind,
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
     * BAT pattern kind classification.
     */
    public enum BatKind {
        REGULAR,
        PERFECT,
        ALTERNATE
    }

    public BatPattern {
        if (direction == null) {
            throw new IllegalArgumentException("BAT pattern must have a non-null direction");
        }
        if (kind == null) {
            throw new IllegalArgumentException("BAT pattern must have a non-null kind");
        }
        if (pivots == null || pivots.size() != 5) {
            throw new IllegalArgumentException("BAT pattern must have exactly 5 pivots (X, A, B, C, D)");
        }
    }
}
