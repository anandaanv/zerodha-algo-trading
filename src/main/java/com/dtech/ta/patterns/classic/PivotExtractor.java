package com.dtech.ta.patterns.classic;

import org.ta4j.core.BarSeries;

import java.util.List;

/**
 * Interface for extracting pivot points from a BarSeries.
 * Reference: docs/spec-classic-patterns-port.md
 */
public interface PivotExtractor {
    /**
     * Extract pivot points from a BarSeries.
     *
     * @param series the BarSeries to analyze
     * @param barsLeft number of bars to the left for local extremum detection
     * @param barsRight number of bars to the right for local extremum detection
     * @param type the type of pivots to extract (HIGH, LOW, or BOTH)
     * @return a list of PivotPoint objects, sorted by barIndex
     */
    List<PivotPoint> extract(BarSeries series, int barsLeft, int barsRight, PivotType type);
}
