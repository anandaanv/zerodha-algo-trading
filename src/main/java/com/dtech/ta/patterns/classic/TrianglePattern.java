package com.dtech.ta.patterns.classic;

import java.time.Instant;
import java.util.List;

/**
 * Triangle pattern record: Ascending, Descending, or Symmetric.
 * 6 pivots (A-F) alternating HIGH-LOW, forming converging or parallel support/resistance.
 * Reference: docs/spec-classic-patterns-port.md (Triangle section)
 */
public record TrianglePattern(
    Direction direction,
    TriangleKind kind,
    List<PivotPoint> pivots,
    int startBarIndex,
    int endBarIndex,
    Instant startTime,
    Instant endTime,
    double avgBarLength
) implements ClassicPattern {

    /**
     * Enumeration of triangle subtypes.
     */
    public enum TriangleKind {
        ASCENDING,
        DESCENDING,
        SYMMETRIC
    }
}
