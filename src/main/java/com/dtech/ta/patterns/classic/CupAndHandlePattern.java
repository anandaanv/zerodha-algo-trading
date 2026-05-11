package com.dtech.ta.patterns.classic;

import java.time.Instant;
import java.util.List;

/**
 * Cup and Handle pattern: bullish reversal/continuation formed by 4 pivots.
 *
 * Pattern structure:
 *   A (LEFT RIM)   - HIGH
 *       |
 *       |   (cup)
 *   B (CUP BOTTOM) - LOW
 *       |
 *       |   (cup)
 *   C (RIGHT RIM)  - HIGH
 *       |
 *       | (handle)
 *   D (HANDLE BOTTOM) - LOW
 *
 * Geometric constraints:
 *   - |A - C| <= avgBarLength (rims at roughly the same level)
 *   - (A - B) > avgBarLength * 2 (cup depth is meaningful, not a V-shape)
 *   - Both C-to-A time AND B-to-A time >= 5 bars (the cup is broad)
 *   - |C - D| < (A - B) * 0.5 (handle is shallower than 50% of the cup)
 *   - Direction: always BULLISH (entry signal is breakout above C, the right rim)
 *
 * Reference: docs/spec-classic-patterns-port.md (Cup and Handle section)
 */
public record CupAndHandlePattern(
    Direction direction,
    List<PivotPoint> pivots,
    int startBarIndex,
    int endBarIndex,
    Instant startTime,
    Instant endTime,
    double avgBarLength
) implements ClassicPattern {

    public CupAndHandlePattern {
        if (direction != Direction.BULLISH) {
            throw new IllegalArgumentException("Cup and Handle pattern must be BULLISH");
        }
        if (pivots == null || pivots.size() != 4) {
            throw new IllegalArgumentException("Cup and Handle pattern must have exactly 4 pivots (A, B, C, D)");
        }
    }
}
