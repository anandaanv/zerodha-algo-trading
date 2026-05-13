package com.dtech.ta.patterns.classic;

import java.time.Instant;
import java.util.List;

/**
 * Reverse Head and Shoulders pattern record: bullish reversal with 5 pivots (A-E) and neckline confirmation.
 * Direction is always BULLISH. F represents the breakout candle (close above right shoulder).
 * Reference: docs/spec-classic-patterns-port.md (Reverse H&S section)
 */
public record ReverseHnsPattern(
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

    public ReverseHnsPattern {
        if (direction != Direction.BULLISH) {
            throw new IllegalArgumentException("Reverse HNS pattern must have BULLISH direction");
        }
    }
}
