package com.dtech.ta.patterns.classic;

import java.time.Instant;
import java.util.List;

/**
 * Bearish Flag pattern: low-pole drop followed by tight consolidation.
 * Mirror of BullishFlagPattern: pole goes down to a new 7-day low, flag consolidates above the Fib 50% retracement.
 * Reference: docs/spec-classic-patterns-port.md (Bearish Flag section)
 */
public record BearishFlagPattern(
    Direction direction,
    List<PivotPoint> pivots,
    int poleStartBarIndex,
    double poleStartPrice,
    int poleLowBarIndex,
    double poleLowPrice,
    int currentBarIndex,
    double currentClose,
    int startBarIndex,
    int endBarIndex,
    Instant startTime,
    Instant endTime,
    double avgBarLength
) implements ClassicPattern {

    @Override
    public Direction direction() {
        return Direction.BEARISH;
    }

    @Override
    public int startBarIndex() {
        return startBarIndex;
    }

    @Override
    public int endBarIndex() {
        return endBarIndex;
    }

    @Override
    public Instant startTime() {
        return startTime;
    }

    @Override
    public Instant endTime() {
        return endTime;
    }

    @Override
    public List<PivotPoint> pivots() {
        return pivots;
    }

    @Override
    public double avgBarLength() {
        return avgBarLength;
    }
}
