package com.dtech.ta.patterns.classic;

import java.time.Instant;
import java.util.List;

/**
 * Trendline pattern: support (uptrend) or resistance (downtrend) line fitted through pivot points.
 * Direction BULLISH indicates uptrend support line (connecting ascending lows).
 * Direction BEARISH indicates downtrend resistance line (connecting descending highs).
 * Reference: docs/spec-classic-patterns-port.md (Trendline section)
 */
public record TrendlinePattern(
    Direction direction,
    Line line,
    List<PivotPoint> pivots,
    int touchpointCount,
    int startBarIndex,
    int endBarIndex,
    Instant startTime,
    Instant endTime,
    double avgBarLength
) implements ClassicPattern {

    @Override
    public Direction direction() {
        return direction;
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
