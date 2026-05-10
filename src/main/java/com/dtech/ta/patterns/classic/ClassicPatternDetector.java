package com.dtech.ta.patterns.classic;

import org.ta4j.core.BarSeries;

import java.util.List;
import java.util.Optional;

/**
 * Generic interface for classic pattern detectors.
 * Reference: docs/spec-classic-patterns-port.md (Single-detector contract)
 */
public interface ClassicPatternDetector<P extends ClassicPattern> {

    /**
     * Find the latest pattern in the series.
     *
     * @param series the BarSeries to analyze
     * @param pivots pre-extracted pivot points
     * @param lookbackBars maximum number of bars to search back
     * @return an Optional containing the latest pattern, if found
     */
    Optional<P> findLatest(BarSeries series, List<PivotPoint> pivots, int lookbackBars);

    /**
     * Find all patterns in the series.
     *
     * @param series the BarSeries to analyze
     * @param pivots pre-extracted pivot points
     * @param lookbackBars maximum number of bars to search back
     * @return a list of all patterns found
     */
    List<P> findAll(BarSeries series, List<PivotPoint> pivots, int lookbackBars);
}
