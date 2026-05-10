package com.dtech.ta.patterns.classic;

import java.time.Instant;
import java.util.List;

/**
 * Double Top pattern record: bearish reversal with twin peaks at similar level, declining volume on second peak.
 * 4 pivots (A-D): A and C are highs, B and D are lows. Direction is always BEARISH.
 * Reference: docs/spec-classic-patterns-port.md (Double Top section)
 */
public record DoubleTopPattern(
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

    public DoubleTopPattern {
        if (direction != Direction.BEARISH) {
            throw new IllegalArgumentException("Double Top pattern must have BEARISH direction");
        }
    }
}
