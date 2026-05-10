package com.dtech.ta.patterns.classic;

import java.time.Instant;
import java.util.List;

/**
 * Bullish Flag pattern: high-pole rally followed by tight consolidation.
 * The pole is the rapid ascent to a new 7-day high; the flag is the pullback consolidation.
 * Reference: docs/spec-classic-patterns-port.md (Bullish Flag section)
 */
public record BullishFlagPattern(
    Direction direction,
    List<PivotPoint> pivots,
    int poleStartBarIndex,
    double poleStartPrice,
    int poleHighBarIndex,
    double poleHighPrice,
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
        return Direction.BULLISH;
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
