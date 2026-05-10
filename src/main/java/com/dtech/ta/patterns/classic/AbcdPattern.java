package com.dtech.ta.patterns.classic;

import java.time.Instant;
import java.util.List;

/**
 * ABCD harmonic pattern: 4 pivots forming a Fibonacci-based extension pattern.
 * Pivots: A (HIGH/LOW), B (LOW/HIGH), C (HIGH/LOW), D (LOW/HIGH, current).
 * Fib retracement C is validated in [0.382, 0.886].
 * Reference: docs/spec-classic-patterns-port.md (ABCD section)
 */
public record AbcdPattern(
    Direction direction,
    AbcdKind kind,
    List<PivotPoint> pivots,
    double cRetrace,
    double terminalPoint,
    int startBarIndex,
    int endBarIndex,
    Instant startTime,
    Instant endTime,
    double avgBarLength
) implements ClassicPattern {

    /**
     * ABCD pattern kind classification.
     */
    public enum AbcdKind {
        REGULAR,
        PERFECT,
        ALTERNATE
    }

    public AbcdPattern {
        if (direction == null) {
            throw new IllegalArgumentException("ABCD pattern must have a non-null direction");
        }
        if (kind == null) {
            throw new IllegalArgumentException("ABCD pattern must have a non-null kind");
        }
        if (pivots == null || pivots.size() != 4) {
            throw new IllegalArgumentException("ABCD pattern must have exactly 4 pivots (A, B, C, D)");
        }
    }
}
