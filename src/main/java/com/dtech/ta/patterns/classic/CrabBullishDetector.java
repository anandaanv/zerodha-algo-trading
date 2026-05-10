package com.dtech.ta.patterns.classic;

import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Detector for Bullish Crab harmonic pattern.
 * 5 pivots: X (LOW) -> A (HIGH) -> B (LOW) -> C (HIGH) -> D (LOW, current).
 * b_retrace must be in {0.382, 0.5, 0.618, 0.886}; c_retrace must be in [0.382, 0.886].
 * Terminal point: 1.618 XA extension.
 * Reference: docs/spec-classic-patterns-port.md (Crab Bullish section)
 */
public class CrabBullishDetector implements ClassicPatternDetector<CrabPattern> {

    private static final double EPSILON = 1e-6;

    @Override
    public Optional<CrabPattern> findLatest(BarSeries series, List<PivotPoint> pivots, int lookbackBars) {
        List<CrabPattern> all = findAll(series, pivots, lookbackBars);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }

    @Override
    public List<CrabPattern> findAll(BarSeries series, List<PivotPoint> pivots, int lookbackBars) {
        List<CrabPattern> patterns = new ArrayList<>();

        if (pivots.size() < 4) {
            return patterns;
        }

        int dBarIndex = series.getEndIndex();
        Bar dBar = series.getBar(dBarIndex);
        double dPrice = dBar.getClosePrice().doubleValue();

        return searchBullish(series, pivots, dBarIndex, dPrice, patterns);
    }

    /**
     * Search for bullish Crab patterns using the X := lowest-pivot-in-series loop.
     */
    private List<CrabPattern> searchBullish(BarSeries series, List<PivotPoint> pivots,
                                            int dBarIndex, double dPrice,
                                            List<CrabPattern> patterns) {
        if (pivots.isEmpty()) {
            return patterns;
        }

        for (int xIdx = 0; xIdx < pivots.size(); xIdx++) {
            PivotPoint xPivot = pivots.get(xIdx);
            double xPrice = xPivot.price();

            int posAfterX = xIdx + 1;

            if (posAfterX >= pivots.size()) {
                continue;
            }

            int aIdx = findHighestPivotIndexAfter(pivots, posAfterX);
            if (aIdx < 0) {
                continue;
            }

            PivotPoint aPivot = pivots.get(aIdx);
            double aPrice = aPivot.price();

            int posAfterA = aIdx + 1;

            if (posAfterA >= pivots.size()) {
                continue;
            }

            int cIdx = findHighestPivotIndexAfter(pivots, posAfterA);
            if (cIdx < 0) {
                continue;
            }

            PivotPoint cPivot = pivots.get(cIdx);
            double cPrice = cPivot.price();

            int bIdx = findLowestPivotIndexInRange(pivots, aIdx, cIdx);
            if (bIdx < 0 || bIdx == cIdx) {
                continue;
            }

            PivotPoint bPivot = pivots.get(bIdx);
            double bPrice = bPivot.price();

            double avgBar = AvgBarLength.median(series, xPivot.barIndex(), cPivot.barIndex());

            if (avgBar == 0) {
                continue;
            }

            if (!isWellFormed(series, xPivot, aPivot, bPivot, cPivot)) {
                continue;
            }

            double xaDiff = aPrice - xPrice;
            double abDiff = aPrice - bPrice;
            double bcDiff = cPrice - bPrice;

            double bRetrace = FibRatios.snapToFib(abDiff / xaDiff);
            double cRetrace = FibRatios.snapToFib(bcDiff / abDiff);

            boolean isPerfectCrab = (Math.abs(bRetrace - 0.618) < EPSILON) &&
                                   ((Math.abs(cRetrace - 0.5) < EPSILON) || (Math.abs(cRetrace - 0.618) < EPSILON));

            boolean isDeepCrab = Math.abs(bRetrace - 0.886) < EPSILON;

            // Rejection rule: if b_retrace > 0.618 and NOT deep_crab, then reject
            if (bRetrace > 0.618 + EPSILON && !isDeepCrab) {
                continue;
            }

            if (bRetrace < 0.382 - EPSILON ||
                cRetrace < 0.382 - EPSILON || cRetrace > 0.886 + EPSILON) {
                continue;
            }

            double xa1618Ext = aPrice - xaDiff * 1.618;
            double terminalPoint = xa1618Ext;

            int abCompletion = bPivot.barIndex() - aPivot.barIndex();
            int cdCompletion = dBarIndex - cPivot.barIndex();

            if (!isValidPattern(series, bPivot, dPrice, terminalPoint, cPivot.barIndex(), dBarIndex, abCompletion, cdCompletion)) {
                continue;
            }

            CrabPattern.CrabKind kind = isPerfectCrab ? CrabPattern.CrabKind.PERFECT :
                                        isDeepCrab ? CrabPattern.CrabKind.DEEP :
                                        CrabPattern.CrabKind.REGULAR;

            List<PivotPoint> patternPivots = new ArrayList<>();
            patternPivots.add(xPivot);
            patternPivots.add(aPivot);
            patternPivots.add(bPivot);
            patternPivots.add(cPivot);

            CrabPattern pattern = new CrabPattern(
                Direction.BULLISH,
                kind,
                patternPivots,
                bRetrace,
                cRetrace,
                terminalPoint,
                xPivot.barIndex(),
                dBarIndex,
                xPivot.time(),
                series.getBar(dBarIndex).getEndTime(),
                avgBar
            );
            patterns.add(pattern);
        }

        return patterns;
    }

    /**
     * Check if Crab is well-formed: highest_xb == a, lowest_ac == b, highest_from_b == c.
     * Also: a not bar's low, b not bar's high, c not bar's low.
     */
    private boolean isWellFormed(BarSeries series, PivotPoint xPivot, PivotPoint aPivot, PivotPoint bPivot, PivotPoint cPivot) {
        Bar aBar = series.getBar(aPivot.barIndex());
        Bar bBar = series.getBar(bPivot.barIndex());
        Bar cBar = series.getBar(cPivot.barIndex());

        if (Math.abs(aPivot.price() - aBar.getLowPrice().doubleValue()) < EPSILON) {
            return false;
        }

        if (Math.abs(bPivot.price() - bBar.getHighPrice().doubleValue()) < EPSILON) {
            return false;
        }

        if (Math.abs(cPivot.price() - cBar.getLowPrice().doubleValue()) < EPSILON) {
            return false;
        }

        return true;
    }

    /**
     * Check if pattern is valid for D: D must retrace significantly, CD completion bounded, terminal testing constraints.
     */
    private boolean isValidPattern(BarSeries series, PivotPoint bPivot, double dPrice, double terminalPoint,
                                   int cBarIndex, int dBarIndex, int abCompletion, int cdCompletion) {
        double bPrice = bPivot.price();

        if (dPrice >= bPrice - (bPrice - terminalPoint) * 0.5) {
            return false;
        }

        int closesBelowTerminal = 0;
        for (int i = cBarIndex; i <= dBarIndex; i++) {
            Bar bar = series.getBar(i);
            if (bar.getClosePrice().doubleValue() < terminalPoint) {
                closesBelowTerminal++;
            }
        }

        if (closesBelowTerminal >= 7) {
            return false;
        }

        Integer firstTestIndex = null;
        for (int i = cBarIndex; i <= dBarIndex; i++) {
            Bar bar = series.getBar(i);
            if (bar.getLowPrice().doubleValue() < terminalPoint) {
                firstTestIndex = i;
                break;
            }
        }

        if (firstTestIndex != null && (dBarIndex - firstTestIndex) >= 7) {
            return false;
        }

        return true;
    }

    private int findHighestPivotIndexAfter(List<PivotPoint> pivots, int fromIdx) {
        int maxIdx = -1;
        double maxPrice = Double.NEGATIVE_INFINITY;

        for (int i = fromIdx; i < pivots.size(); i++) {
            if (pivots.get(i).price() > maxPrice) {
                maxPrice = pivots.get(i).price();
                maxIdx = i;
            }
        }

        return maxIdx;
    }

    private int findLowestPivotIndexInRange(List<PivotPoint> pivots, int fromIdx, int toIdx) {
        int minIdx = -1;
        double minPrice = Double.POSITIVE_INFINITY;

        for (int i = fromIdx; i <= toIdx && i < pivots.size(); i++) {
            if (pivots.get(i).price() < minPrice) {
                minPrice = pivots.get(i).price();
                minIdx = i;
            }
        }

        return minIdx;
    }
}
