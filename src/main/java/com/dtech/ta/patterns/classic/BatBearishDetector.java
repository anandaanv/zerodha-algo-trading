package com.dtech.ta.patterns.classic;

import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Detector for Bearish BAT harmonic pattern.
 * 5 pivots: X (HIGH) -> A (LOW) -> B (HIGH) -> C (LOW) -> D (HIGH, current).
 * b_retrace must be in {0.382, 0.5}; c_retrace must be in [0.382, 0.886].
 * Reference: docs/spec-classic-patterns-port.md (BAT Bearish section)
 */
public class BatBearishDetector implements ClassicPatternDetector<BatPattern> {

    private static final double EPSILON = 1e-6;

    @Override
    public Optional<BatPattern> findLatest(BarSeries series, List<PivotPoint> pivots, int lookbackBars) {
        List<BatPattern> all = findAll(series, pivots, lookbackBars);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }

    @Override
    public List<BatPattern> findAll(BarSeries series, List<PivotPoint> pivots, int lookbackBars) {
        List<BatPattern> patterns = new ArrayList<>();

        if (pivots.size() < 4) {
            return patterns;
        }

        int dBarIndex = series.getEndIndex();
        Bar dBar = series.getBar(dBarIndex);
        double dPrice = dBar.getClosePrice().doubleValue();

        return searchBearish(series, pivots, dBarIndex, dPrice, patterns);
    }

    /**
     * Search for bearish BAT patterns using the X := highest-pivot-after-X advance loop.
     */
    private List<BatPattern> searchBearish(BarSeries series, List<PivotPoint> pivots,
                                           int dBarIndex, double dPrice,
                                           List<BatPattern> patterns) {
        if (pivots.isEmpty()) {
            return patterns;
        }

        int xIdx = findHighestPivotIndex(pivots);
        if (xIdx < 0) {
            return patterns;
        }

        PivotPoint xPivot = pivots.get(xIdx);
        double xPrice = xPivot.price();

        while (true) {
            int posAfterX = xIdx + 1;

            if (posAfterX >= pivots.size()) {
                break;
            }

            int aIdx = findLowestPivotIndexAfter(pivots, posAfterX);
            if (aIdx < 0) {
                break;
            }

            PivotPoint aPivot = pivots.get(aIdx);
            double aPrice = aPivot.price();

            int posAfterA = aIdx + 1;

            if (posAfterA >= pivots.size()) {
                break;
            }

            int cIdx = findLowestPivotIndexAfter(pivots, posAfterA);
            if (cIdx < 0) {
                break;
            }

            PivotPoint cPivot = pivots.get(cIdx);
            double cPrice = cPivot.price();

            int bIdx = findHighestPivotIndexInRange(pivots, aIdx, cIdx);
            if (bIdx < 0 || bIdx == cIdx) {
                xIdx = findHighestPivotIndexAfter(pivots, posAfterX);
                if (xIdx < 0) break;
                xPivot = pivots.get(xIdx);
                xPrice = xPivot.price();
                continue;
            }

            PivotPoint bPivot = pivots.get(bIdx);
            double bPrice = bPivot.price();

            double avgBar = AvgBarLength.median(series, xPivot.barIndex(), cPivot.barIndex());

            if (avgBar == 0) {
                xIdx = findHighestPivotIndexAfter(pivots, posAfterX);
                if (xIdx < 0) break;
                xPivot = pivots.get(xIdx);
                xPrice = xPivot.price();
                continue;
            }

            if (!isWellFormed(series, xPivot, aPivot, bPivot, cPivot)) {
                xIdx = findHighestPivotIndexAfter(pivots, posAfterX);
                if (xIdx < 0) break;
                xPivot = pivots.get(xIdx);
                xPrice = xPivot.price();
                continue;
            }

            double xaDiff = xPrice - aPrice;
            double abDiff = bPrice - aPrice;
            double bcDiff = bPrice - cPrice;

            double bRetrace = FibRatios.snapToFib(abDiff / xaDiff);
            double cRetrace = FibRatios.snapToFib(bcDiff / abDiff);

            boolean isPerfect = (Math.abs(bRetrace - 0.5) < EPSILON) &&
                               (Math.abs(cRetrace - 0.5) < EPSILON || Math.abs(cRetrace - 0.618) < EPSILON);
            boolean isAlternate = Math.abs(bRetrace - 0.382) < EPSILON;

            if (bRetrace < 0.382 - EPSILON || bRetrace > 0.5 + EPSILON ||
                cRetrace < 0.382 - EPSILON || cRetrace > 0.886 + EPSILON) {
                xIdx = findHighestPivotIndexAfter(pivots, posAfterX);
                if (xIdx < 0) break;
                xPivot = pivots.get(xIdx);
                xPrice = xPivot.price();
                continue;
            }

            if (!isAlternate) {
                double highestCloseFromC = findHighestCloseFromIndex(series, cPivot.barIndex(), dBarIndex);
                if (highestCloseFromC > xPrice + EPSILON) {
                    xIdx = findHighestPivotIndexAfter(pivots, posAfterX);
                    if (xIdx < 0) break;
                    xPivot = pivots.get(xIdx);
                    xPrice = xPivot.price();
                    continue;
                }
            }

            double xa886Retrace = aPrice + xaDiff * 0.886;
            double xa13Ext = aPrice + xaDiff * 1.13;

            double terminalPoint = isAlternate ? xa13Ext : xa886Retrace;

            int abCompletion = bPivot.barIndex() - aPivot.barIndex();
            int cdCompletion = dBarIndex - cPivot.barIndex();

            if (!isValidPattern(series, bPivot, dPrice, terminalPoint, cPivot.barIndex(), dBarIndex, abCompletion, cdCompletion)) {
                xIdx = findHighestPivotIndexAfter(pivots, posAfterX);
                if (xIdx < 0) break;
                xPivot = pivots.get(xIdx);
                xPrice = xPivot.price();
                continue;
            }

            BatPattern.BatKind kind = isPerfect ? BatPattern.BatKind.PERFECT :
                                      isAlternate ? BatPattern.BatKind.ALTERNATE :
                                      BatPattern.BatKind.REGULAR;

            List<PivotPoint> patternPivots = new ArrayList<>();
            patternPivots.add(xPivot);
            patternPivots.add(aPivot);
            patternPivots.add(bPivot);
            patternPivots.add(cPivot);

            BatPattern pattern = new BatPattern(
                Direction.BEARISH,
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

            xIdx = findHighestPivotIndexAfter(pivots, posAfterX);
            if (xIdx < 0) break;
            xPivot = pivots.get(xIdx);
            xPrice = xPivot.price();
        }

        return patterns;
    }

    /**
     * Check if BAT is well-formed: lowest_ab == a, highest_ac == b, lowest_from_b == c.
     * Also: x not bar's low, a not bar's high, b not bar's low, c not bar's high.
     */
    private boolean isWellFormed(BarSeries series, PivotPoint xPivot, PivotPoint aPivot, PivotPoint bPivot, PivotPoint cPivot) {
        Bar xBar = series.getBar(xPivot.barIndex());
        Bar aBar = series.getBar(aPivot.barIndex());
        Bar bBar = series.getBar(bPivot.barIndex());
        Bar cBar = series.getBar(cPivot.barIndex());

        if (Math.abs(xPivot.price() - xBar.getLowPrice().doubleValue()) < EPSILON) {
            return false;
        }

        if (Math.abs(aPivot.price() - aBar.getHighPrice().doubleValue()) < EPSILON) {
            return false;
        }

        if (Math.abs(bPivot.price() - bBar.getLowPrice().doubleValue()) < EPSILON) {
            return false;
        }

        if (Math.abs(cPivot.price() - cBar.getHighPrice().doubleValue()) < EPSILON) {
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

        if (dPrice <= bPrice + (terminalPoint - bPrice) * 0.5) {
            return false;
        }

        int closesAboveTerminal = 0;
        for (int i = cBarIndex; i <= dBarIndex; i++) {
            Bar bar = series.getBar(i);
            if (bar.getClosePrice().doubleValue() > terminalPoint) {
                closesAboveTerminal++;
            }
        }

        if (closesAboveTerminal >= 7) {
            return false;
        }

        Integer firstTestIndex = null;
        for (int i = cBarIndex; i <= dBarIndex; i++) {
            Bar bar = series.getBar(i);
            if (bar.getHighPrice().doubleValue() > terminalPoint) {
                firstTestIndex = i;
                break;
            }
        }

        if (firstTestIndex != null && (dBarIndex - firstTestIndex) >= 7) {
            return false;
        }

        return true;
    }

    private double findHighestCloseFromIndex(BarSeries series, int fromIndex, int toIndex) {
        double maxClose = Double.NEGATIVE_INFINITY;
        for (int i = fromIndex; i <= toIndex; i++) {
            Bar bar = series.getBar(i);
            maxClose = Math.max(maxClose, bar.getClosePrice().doubleValue());
        }
        return maxClose;
    }

    private int findHighestPivotIndex(List<PivotPoint> pivots) {
        int maxIdx = -1;
        double maxPrice = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < pivots.size(); i++) {
            if (pivots.get(i).price() > maxPrice) {
                maxPrice = pivots.get(i).price();
                maxIdx = i;
            }
        }

        return maxIdx;
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

    private int findLowestPivotIndexAfter(List<PivotPoint> pivots, int fromIdx) {
        int minIdx = -1;
        double minPrice = Double.POSITIVE_INFINITY;

        for (int i = fromIdx; i < pivots.size(); i++) {
            if (pivots.get(i).price() < minPrice) {
                minPrice = pivots.get(i).price();
                minIdx = i;
            }
        }

        return minIdx;
    }

    private int findHighestPivotIndexInRange(List<PivotPoint> pivots, int fromIdx, int toIdx) {
        int maxIdx = -1;
        double maxPrice = Double.NEGATIVE_INFINITY;

        for (int i = fromIdx; i <= toIdx && i < pivots.size(); i++) {
            if (pivots.get(i).price() > maxPrice) {
                maxPrice = pivots.get(i).price();
                maxIdx = i;
            }
        }

        return maxIdx;
    }
}
