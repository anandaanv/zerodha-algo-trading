package com.dtech.ta.patterns.classic;

import java.time.Instant;
import java.util.List;

/**
 * Head and Shoulders pattern record: bearish reversal with 5 pivots (A-E) and neckline confirmation.
 * Direction is always BEARISH. F represents the breakout candle (close below right shoulder).
 * Reference: docs/spec-classic-patterns-port.md (Head & Shoulders section)
 */
public record HnsPattern(
    Direction direction,
    List<PivotPoint> pivots,
    int fBarIndex,
    double fPrice,
    int startBarIndex,
    int endBarIndex,
    Instant startTime,
    Instant endTime,
    double avgBarLength
) implements ClassicPattern {

    public HnsPattern {
        if (direction != Direction.BEARISH) {
            throw new IllegalArgumentException("HNS pattern must have BEARISH direction");
        }
    }
}
