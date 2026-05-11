package com.dtech.ta.patterns.classic;

import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Orchestrator for scanning a BarSeries for classic chart patterns.
 * Phase 2 injects detectors for Triangle, HNS, Reverse HNS, Double Top, Double Bottom.
 * Detectors are isolated: exceptions in one detector do not prevent subsequent detectors from running.
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
     * Each detector runs in isolation: exceptions in one detector are logged to stderr but do not prevent
     * subsequent detectors from running.
     *
     * @param lookbackBars maximum number of bars to search back
     * @param include set of PatternType values to include
     * @return list of detected patterns (union of all detectors that did not fail)
     */
    public List<ClassicPattern> scan(int lookbackBars, EnumSet<PatternType> include) {
        List<ClassicPattern> results = new ArrayList<>();

        List<PivotPoint> pivots = pivotExtractor.extract(series, 6, 6, PivotType.BOTH);

        if (include.contains(PatternType.TRIANGLE)) {
            try {
                TriangleClassicDetector triangleDetector = new TriangleClassicDetector();
                results.addAll(triangleDetector.findAll(series, pivots, lookbackBars));
            } catch (Exception e) {
                System.err.println("Detector TRIANGLE failed for " + series.getName() + ": " + e.getMessage());
            }
        }

        if (include.contains(PatternType.HNS)) {
            try {
                HnsClassicDetector hnsDetector = new HnsClassicDetector();
                results.addAll(hnsDetector.findAll(series, pivots, lookbackBars));
            } catch (Exception e) {
                System.err.println("Detector HNS failed for " + series.getName() + ": " + e.getMessage());
            }
        }

        if (include.contains(PatternType.REVERSE_HNS)) {
            try {
                ReverseHnsClassicDetector reverseHnsDetector = new ReverseHnsClassicDetector();
                results.addAll(reverseHnsDetector.findAll(series, pivots, lookbackBars));
            } catch (Exception e) {
                System.err.println("Detector REVERSE_HNS failed for " + series.getName() + ": " + e.getMessage());
            }
        }

        if (include.contains(PatternType.DOUBLE_TOP)) {
            try {
                DoubleTopClassicDetector doubleTopDetector = new DoubleTopClassicDetector();
                results.addAll(doubleTopDetector.findAll(series, pivots, lookbackBars));
            } catch (Exception e) {
                System.err.println("Detector DOUBLE_TOP failed for " + series.getName() + ": " + e.getMessage());
            }
        }

        if (include.contains(PatternType.DOUBLE_BOTTOM)) {
            try {
                DoubleBottomClassicDetector doubleBottomDetector = new DoubleBottomClassicDetector();
                results.addAll(doubleBottomDetector.findAll(series, pivots, lookbackBars));
            } catch (Exception e) {
                System.err.println("Detector DOUBLE_BOTTOM failed for " + series.getName() + ": " + e.getMessage());
            }
        }

        if (include.contains(PatternType.BULLISH_VCP)) {
            try {
                BullishVcpDetector bullishVcpDetector = new BullishVcpDetector();
                results.addAll(bullishVcpDetector.findAll(series, pivots, lookbackBars));
            } catch (Exception e) {
                System.err.println("Detector BULLISH_VCP failed for " + series.getName() + ": " + e.getMessage());
            }
        }

        if (include.contains(PatternType.BEARISH_VCP)) {
            try {
                BearishVcpDetector bearishVcpDetector = new BearishVcpDetector();
                results.addAll(bearishVcpDetector.findAll(series, pivots, lookbackBars));
            } catch (Exception e) {
                System.err.println("Detector BEARISH_VCP failed for " + series.getName() + ": " + e.getMessage());
            }
        }

        if (include.contains(PatternType.BULLISH_FLAG)) {
            try {
                BullishFlagClassicDetector bullishFlagDetector = new BullishFlagClassicDetector();
                results.addAll(bullishFlagDetector.findAll(series, pivots, lookbackBars));
            } catch (Exception e) {
                System.err.println("Detector BULLISH_FLAG failed for " + series.getName() + ": " + e.getMessage());
            }
        }

        if (include.contains(PatternType.BEARISH_FLAG)) {
            try {
                BearishFlagClassicDetector bearishFlagDetector = new BearishFlagClassicDetector();
                results.addAll(bearishFlagDetector.findAll(series, pivots, lookbackBars));
            } catch (Exception e) {
                System.err.println("Detector BEARISH_FLAG failed for " + series.getName() + ": " + e.getMessage());
            }
        }

        if (include.contains(PatternType.UPTREND_LINE)) {
            try {
                UptrendLineDetector uptrendLineDetector = new UptrendLineDetector();
                results.addAll(uptrendLineDetector.findAll(series, pivots, lookbackBars));
            } catch (Exception e) {
                System.err.println("Detector UPTREND_LINE failed for " + series.getName() + ": " + e.getMessage());
            }
        }

        if (include.contains(PatternType.DOWNTREND_LINE)) {
            try {
                DowntrendLineDetector downtrendLineDetector = new DowntrendLineDetector();
                results.addAll(downtrendLineDetector.findAll(series, pivots, lookbackBars));
            } catch (Exception e) {
                System.err.println("Detector DOWNTREND_LINE failed for " + series.getName() + ": " + e.getMessage());
            }
        }

        if (include.contains(PatternType.CUP_AND_HANDLE)) {
            try {
                CupAndHandleDetector cupAndHandleDetector = new CupAndHandleDetector();
                results.addAll(cupAndHandleDetector.findAll(series, pivots, lookbackBars));
            } catch (Exception e) {
                System.err.println("Detector CUP_AND_HANDLE failed for " + series.getName() + ": " + e.getMessage());
            }
        }

        if (include.contains(PatternType.BULLISH_ABCD)) {
            try {
                AbcdBullishDetector abcdBullishDetector = new AbcdBullishDetector();
                results.addAll(abcdBullishDetector.findAll(series, pivots, lookbackBars));
            } catch (Exception e) {
                System.err.println("Detector BULLISH_ABCD failed for " + series.getName() + ": " + e.getMessage());
            }
        }

        if (include.contains(PatternType.BEARISH_ABCD)) {
            try {
                AbcdBearishDetector abcdBearishDetector = new AbcdBearishDetector();
                results.addAll(abcdBearishDetector.findAll(series, pivots, lookbackBars));
            } catch (Exception e) {
                System.err.println("Detector BEARISH_ABCD failed for " + series.getName() + ": " + e.getMessage());
            }
        }

        if (include.contains(PatternType.BULLISH_BAT)) {
            try {
                BatBullishDetector batBullishDetector = new BatBullishDetector();
                results.addAll(batBullishDetector.findAll(series, pivots, lookbackBars));
            } catch (Exception e) {
                System.err.println("Detector BULLISH_BAT failed for " + series.getName() + ": " + e.getMessage());
            }
        }

        if (include.contains(PatternType.BEARISH_BAT)) {
            try {
                BatBearishDetector batBearishDetector = new BatBearishDetector();
                results.addAll(batBearishDetector.findAll(series, pivots, lookbackBars));
            } catch (Exception e) {
                System.err.println("Detector BEARISH_BAT failed for " + series.getName() + ": " + e.getMessage());
            }
        }

        if (include.contains(PatternType.BULLISH_GARTLEY)) {
            try {
                GartleyBullishDetector gartleyBullishDetector = new GartleyBullishDetector();
                results.addAll(gartleyBullishDetector.findAll(series, pivots, lookbackBars));
            } catch (Exception e) {
                System.err.println("Detector BULLISH_GARTLEY failed for " + series.getName() + ": " + e.getMessage());
            }
        }

        if (include.contains(PatternType.BEARISH_GARTLEY)) {
            try {
                GartleyBearishDetector gartleyBearishDetector = new GartleyBearishDetector();
                results.addAll(gartleyBearishDetector.findAll(series, pivots, lookbackBars));
            } catch (Exception e) {
                System.err.println("Detector BEARISH_GARTLEY failed for " + series.getName() + ": " + e.getMessage());
            }
        }

        if (include.contains(PatternType.BULLISH_CRAB)) {
            try {
                CrabBullishDetector crabBullishDetector = new CrabBullishDetector();
                results.addAll(crabBullishDetector.findAll(series, pivots, lookbackBars));
            } catch (Exception e) {
                System.err.println("Detector BULLISH_CRAB failed for " + series.getName() + ": " + e.getMessage());
            }
        }

        if (include.contains(PatternType.BEARISH_CRAB)) {
            try {
                CrabBearishDetector crabBearishDetector = new CrabBearishDetector();
                results.addAll(crabBearishDetector.findAll(series, pivots, lookbackBars));
            } catch (Exception e) {
                System.err.println("Detector BEARISH_CRAB failed for " + series.getName() + ": " + e.getMessage());
            }
        }

        if (include.contains(PatternType.BULLISH_BUTTERFLY)) {
            try {
                ButterflyBullishDetector butterflyBullishDetector = new ButterflyBullishDetector();
                results.addAll(butterflyBullishDetector.findAll(series, pivots, lookbackBars));
            } catch (Exception e) {
                System.err.println("Detector BULLISH_BUTTERFLY failed for " + series.getName() + ": " + e.getMessage());
            }
        }

        if (include.contains(PatternType.BEARISH_BUTTERFLY)) {
            try {
                ButterflyBearishDetector butterflyBearishDetector = new ButterflyBearishDetector();
                results.addAll(butterflyBearishDetector.findAll(series, pivots, lookbackBars));
            } catch (Exception e) {
                System.err.println("Detector BEARISH_BUTTERFLY failed for " + series.getName() + ": " + e.getMessage());
            }
        }

        return results;
    }

    /**
     * Scan the series for all pattern types.
     * Each detector runs in isolation: exceptions in one detector are logged but do not prevent
     * subsequent detectors from running.
     *
     * @param lookbackBars maximum number of bars to search back
     * @return list of detected patterns (union of all detectors that did not fail)
     */
    public List<ClassicPattern> scanAll(int lookbackBars) {
        return scan(lookbackBars, EnumSet.allOf(PatternType.class));
    }
}
