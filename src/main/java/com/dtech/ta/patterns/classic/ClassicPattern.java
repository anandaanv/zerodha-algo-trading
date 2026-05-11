package com.dtech.ta.patterns.classic;

import java.time.Instant;
import java.util.List;

/**
 * Sealed interface representing a classic chart pattern.
 * Concrete implementations: Triangle, HNS, Reverse HNS, Double Top, Double Bottom (Phase 2).
 * Future phases: VCP, Flag, Trendline, Harmonic patterns.
 * Reference: docs/spec-classic-patterns-port.md (Core API design)
 */
public sealed interface ClassicPattern
    permits TrianglePattern, HnsPattern, ReverseHnsPattern, DoubleTopPattern, DoubleBottomPattern,
        BullishVcpPattern, BearishVcpPattern,
        BullishFlagPattern, BearishFlagPattern, TrendlinePattern,
        CupAndHandlePattern,
        AbcdPattern, BatPattern,
        GartleyPattern, CrabPattern, ButterflyPattern {

    /**
     * Direction of the pattern (BULLISH or BEARISH).
     */
    Direction direction();

    /**
     * Bar index where the pattern starts.
     */
    int startBarIndex();

    /**
     * Bar index where the pattern ends.
     */
    int endBarIndex();

    /**
     * Timestamp of the first bar in the pattern.
     */
    Instant startTime();

    /**
     * Timestamp of the last bar in the pattern.
     */
    Instant endTime();

    /**
     * Pivot points forming the pattern, ordered (A, B, C, ...).
     */
    List<PivotPoint> pivots();

    /**
     * Average bar length over the pattern range.
     */
    double avgBarLength();
}
