package com.dtech.ta.patterns.classic;

import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Orchestrator for scanning a BarSeries for classic chart patterns.
 * Phase 2 injects detectors for Triangle, HNS, Reverse HNS, Double Top, Double Bottom.
 * Reference: docs/spec-classic-patterns-port.md (Scanner aggregator)
 */
public class ClassicPatternScanner {

    private final BarSeries series;
    private final PivotExtractor pivotExtractor;

    /**
     * Create a scanner for a given BarSeries.
     *
     * @param series the BarSeries to analyze
     * @param pivotExtractor the pivot extraction strategy
     */
    public ClassicPatternScanner(BarSeries series, PivotExtractor pivotExtractor) {
        this.series = series;
        this.pivotExtractor = pivotExtractor;
    }

    /**
     * Scan the series for a subset of pattern types.
     *
     * @param lookbackBars maximum number of bars to search back
     * @param include set of PatternType values to include
     * @return list of detected patterns
     */
    public List<ClassicPattern> scan(int lookbackBars, EnumSet<PatternType> include) {
        List<ClassicPattern> results = new ArrayList<>();

        List<PivotPoint> pivots = pivotExtractor.extract(series, 6, 6, PivotType.BOTH);

        if (include.contains(PatternType.TRIANGLE)) {
            TriangleClassicDetector triangleDetector = new TriangleClassicDetector();
            results.addAll(triangleDetector.findAll(series, pivots, lookbackBars));
        }

        if (include.contains(PatternType.HNS)) {
            HnsClassicDetector hnsDetector = new HnsClassicDetector();
            results.addAll(hnsDetector.findAll(series, pivots, lookbackBars));
        }

        if (include.contains(PatternType.REVERSE_HNS)) {
            ReverseHnsClassicDetector reverseHnsDetector = new ReverseHnsClassicDetector();
            results.addAll(reverseHnsDetector.findAll(series, pivots, lookbackBars));
        }

        if (include.contains(PatternType.DOUBLE_TOP)) {
            DoubleTopClassicDetector doubleTopDetector = new DoubleTopClassicDetector();
            results.addAll(doubleTopDetector.findAll(series, pivots, lookbackBars));
        }

        if (include.contains(PatternType.DOUBLE_BOTTOM)) {
            DoubleBottomClassicDetector doubleBottomDetector = new DoubleBottomClassicDetector();
            results.addAll(doubleBottomDetector.findAll(series, pivots, lookbackBars));
        }

        if (include.contains(PatternType.BULLISH_VCP)) {
            BullishVcpDetector bullishVcpDetector = new BullishVcpDetector();
            results.addAll(bullishVcpDetector.findAll(series, pivots, lookbackBars));
        }

        if (include.contains(PatternType.BEARISH_VCP)) {
            BearishVcpDetector bearishVcpDetector = new BearishVcpDetector();
            results.addAll(bearishVcpDetector.findAll(series, pivots, lookbackBars));
        }

        if (include.contains(PatternType.BULLISH_FLAG)) {
            BullishFlagClassicDetector bullishFlagDetector = new BullishFlagClassicDetector();
            results.addAll(bullishFlagDetector.findAll(series, pivots, lookbackBars));
        }

        if (include.contains(PatternType.BEARISH_FLAG)) {
            BearishFlagClassicDetector bearishFlagDetector = new BearishFlagClassicDetector();
            results.addAll(bearishFlagDetector.findAll(series, pivots, lookbackBars));
        }

        if (include.contains(PatternType.UPTREND_LINE)) {
            UptrendLineDetector uptrendLineDetector = new UptrendLineDetector();
            results.addAll(uptrendLineDetector.findAll(series, pivots, lookbackBars));
        }

        if (include.contains(PatternType.DOWNTREND_LINE)) {
            DowntrendLineDetector downtrendLineDetector = new DowntrendLineDetector();
            results.addAll(downtrendLineDetector.findAll(series, pivots, lookbackBars));
        }

        if (include.contains(PatternType.BULLISH_ABCD)) {
            AbcdBullishDetector abcdBullishDetector = new AbcdBullishDetector();
            results.addAll(abcdBullishDetector.findAll(series, pivots, lookbackBars));
        }

        if (include.contains(PatternType.BEARISH_ABCD)) {
            AbcdBearishDetector abcdBearishDetector = new AbcdBearishDetector();
            results.addAll(abcdBearishDetector.findAll(series, pivots, lookbackBars));
        }

        if (include.contains(PatternType.BULLISH_BAT)) {
            BatBullishDetector batBullishDetector = new BatBullishDetector();
            results.addAll(batBullishDetector.findAll(series, pivots, lookbackBars));
        }

        if (include.contains(PatternType.BEARISH_BAT)) {
            BatBearishDetector batBearishDetector = new BatBearishDetector();
            results.addAll(batBearishDetector.findAll(series, pivots, lookbackBars));
        }

        if (include.contains(PatternType.BULLISH_GARTLEY)) {
            GartleyBullishDetector gartleyBullishDetector = new GartleyBullishDetector();
            results.addAll(gartleyBullishDetector.findAll(series, pivots, lookbackBars));
        }

        if (include.contains(PatternType.BEARISH_GARTLEY)) {
            GartleyBearishDetector gartleyBearishDetector = new GartleyBearishDetector();
            results.addAll(gartleyBearishDetector.findAll(series, pivots, lookbackBars));
        }

        if (include.contains(PatternType.BULLISH_CRAB)) {
            CrabBullishDetector crabBullishDetector = new CrabBullishDetector();
            results.addAll(crabBullishDetector.findAll(series, pivots, lookbackBars));
        }

        if (include.contains(PatternType.BEARISH_CRAB)) {
            CrabBearishDetector crabBearishDetector = new CrabBearishDetector();
            results.addAll(crabBearishDetector.findAll(series, pivots, lookbackBars));
        }

        if (include.contains(PatternType.BULLISH_BUTTERFLY)) {
            ButterflyBullishDetector butterflyBullishDetector = new ButterflyBullishDetector();
            results.addAll(butterflyBullishDetector.findAll(series, pivots, lookbackBars));
        }

        if (include.contains(PatternType.BEARISH_BUTTERFLY)) {
            ButterflyBearishDetector butterflyBearishDetector = new ButterflyBearishDetector();
            results.addAll(butterflyBearishDetector.findAll(series, pivots, lookbackBars));
        }

        return results;
    }

    /**
     * Scan the series for all pattern types.
     *
     * @param lookbackBars maximum number of bars to search back
     * @return list of detected patterns
     */
    public List<ClassicPattern> scanAll(int lookbackBars) {
        return scan(lookbackBars, EnumSet.allOf(PatternType.class));
    }
}
