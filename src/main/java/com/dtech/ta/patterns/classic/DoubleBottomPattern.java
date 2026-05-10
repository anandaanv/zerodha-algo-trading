package com.dtech.ta.patterns.classic;

import java.time.Instant;
import java.util.List;

/**
 * Double Bottom pattern record: bullish reversal with twin valleys at similar level, declining volume on second valley.
 * 4 pivots (A-D): A and C are lows, B and D are highs. Direction is always BULLISH.
 * Reference: docs/spec-classic-patterns-port.md (Double Bottom section)
 */
public record DoubleBottomPattern(
    Direction direction,
    List<PivotPoint> pivots,
    double aVolume,
    double cVolume,
    double atr,
    int startBarIndex,
    int endBarIndex,
    Instant startTime,
    Instant endTime,
    double avgBarLength
) implements ClassicPattern {

    public DoubleBottomPattern {
        if (direction != Direction.BULLISH) {
            throw new IllegalArgumentException("Double Bottom pattern must have BULLISH direction");
        }
    }
}
