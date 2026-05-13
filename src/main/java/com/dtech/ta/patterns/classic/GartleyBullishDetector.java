package com.dtech.ta.patterns.classic;

import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Detector for Bullish Gartley harmonic pattern.
 * 5 pivots: X (LOW) -> A (HIGH) -> B (LOW) -> C (HIGH) -> D (LOW, current).
 * b_retrace must be == 0.618; c_retrace must be in [0.382, 0.886].
 * Terminal point: 0.786 XA retrace.
 * Reference: docs/spec-classic-patterns-port.md (Gartley Bullish section)
 */
public class GartleyBullishDetector implements ClassicPatternDetector<GartleyPattern> {

    private static final double EPSILON = 1e-6;

    @Override
    public Optional<GartleyPattern> findLatest(BarSeries series, List<PivotPoint> pivots, int lookbackBars) {
        List<GartleyPattern> all = findAll(series, pivots, lookbackBars);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }

    @Override
    public List<GartleyPattern> findAll(BarSeries series, List<PivotPoint> pivots, int lookbackBars) {
        List<GartleyPattern> patterns = new ArrayList<>();

        if (pivots.size() < 4) {
            return patterns;
        }

        int dBarIndex = series.getEndIndex();
        Bar dBar = series.getBar(dBarIndex);
        double dPrice = dBar.getClosePrice().doubleValue();

        return searchBullish(series, pivots, dBarIndex, dPrice, patterns);
    }

    /**
     * Search for bullish Gartley patterns using the X := lowest-pivot-after-X advance loop.
     */
    private List<GartleyPattern> searchBullish(BarSeries series, List<PivotPoint> pivots,
                                               int dBarIndex, double dPrice,
                                               List<GartleyPattern> patterns) {
        if (pivots.isEmpty()) {
            return patterns;
        }

        int xIdx = findLowestPivotIndex(pivots);
        if (xIdx < 0) {
            return patterns;
        }

        PivotPoint xPivot = pivots.get(xIdx);
        if (xPivot.type() != PivotType.LOW) {
            xIdx = findLowestPivotIndexAfter(pivots, xIdx + 1);
            if (xIdx < 0) return patterns;
            xPivot = pivots.get(xIdx);
        }
        double xPrice = xPivot.price();

        while (true) {
            int posAfterX = xIdx + 1;

            if (posAfterX >= pivots.size()) {
                break;
            }

            int aIdx = findHighestPivotIndexAfter(pivots, posAfterX);
            if (aIdx < 0) {
                break;
            }

            PivotPoint aPivot = pivots.get(aIdx);
            double aPrice = aPivot.price();

            int posAfterA = aIdx + 1;

            if (posAfterA >= pivots.size()) {
                break;
            }

            int cIdx = findHighestPivotIndexAfter(pivots, posAfterA);
            if (cIdx < 0) {
                break;
            }

            PivotPoint cPivot = pivots.get(cIdx);
            double cPrice = cPivot.price();

            int bIdx = findLowestPivotIndexInRange(pivots, aIdx, cIdx);
            if (bIdx < 0 || bIdx == cIdx) {
                xIdx = findLowestPivotIndexAfter(pivots, posAfterX);
                if (xIdx < 0) break;
                xPivot = pivots.get(xIdx);
                xPrice = xPivot.price();
                continue;
            }

            PivotPoint bPivot = pivots.get(bIdx);
            double bPrice = bPivot.price();

            double avgBar = AvgBarLength.median(series, xPivot.barIndex(), cPivot.barIndex());

            if (avgBar == 0) {
                xIdx = findLowestPivotIndexAfter(pivots, posAfterX);
                if (xIdx < 0) break;
                xPivot = pivots.get(xIdx);
                xPrice = xPivot.price();
                continue;
            }

            if (!isWellFormed(series, xPivot, aPivot, bPivot, cPivot)) {
                xIdx = findLowestPivotIndexAfter(pivots, posAfterX);
                if (xIdx < 0) break;
                xPivot = pivots.get(xIdx);
                xPrice = xPivot.price();
                continue;
            }

            double xaDiff = aPrice - xPrice;
            double abDiff = aPrice - bPrice;
            double bcDiff = cPrice - bPrice;

            double bRetrace = FibRatios.snapToFib(abDiff / xaDiff);
            double cRetrace = FibRatios.snapToFib(bcDiff / abDiff);

            boolean isPerfect = (Math.abs(bRetrace - 0.618) < EPSILON) &&
                               (Math.abs(cRetrace - 0.618) < EPSILON);

            if (Math.abs(bRetrace - 0.618) > EPSILON ||
                cRetrace < 0.382 - EPSILON || cRetrace > 0.886 + EPSILON) {
                xIdx = findLowestPivotIndexAfter(pivots, posAfterX);
                if (xIdx < 0) break;
                xPivot = pivots.get(xIdx);
                xPrice = xPivot.price();
                continue;
            }

            double lowestCloseFromC = findLowestCloseFromIndex(series, cPivot.barIndex(), dBarIndex);
            if (lowestCloseFromC < xPrice - EPSILON) {
                xIdx = findLowestPivotIndexAfter(pivots, posAfterX);
                if (xIdx < 0) break;
                xPivot = pivots.get(xIdx);
                xPrice = xPivot.price();
                continue;
            }

            double xa786Retrace = aPrice - xaDiff * 0.786;
            double terminalPoint = xa786Retrace;

            int abCompletion = bPivot.barIndex() - aPivot.barIndex();
            int cdCompletion = dBarIndex - cPivot.barIndex();

            if (!isValidPattern(series, bPivot, dPrice, terminalPoint, cPivot.barIndex(), dBarIndex, abCompletion, cdCompletion)) {
                xIdx = findLowestPivotIndexAfter(pivots, posAfterX);
                if (xIdx < 0) break;
                xPivot = pivots.get(xIdx);
                xPrice = xPivot.price();
                continue;
            }

            GartleyPattern.GartleyKind kind = isPerfect ? GartleyPattern.GartleyKind.PERFECT :
                                              GartleyPattern.GartleyKind.REGULAR;

            List<PivotPoint> patternPivots = new ArrayList<>();
            patternPivots.add(xPivot);
            patternPivots.add(aPivot);
            patternPivots.add(bPivot);
            patternPivots.add(cPivot);
            // Add synthetic D pivot at current bar
            PivotPoint dPivot = new PivotPoint(dBarIndex, series.getBar(dBarIndex).getEndTime(), dPrice, PivotType.LOW);
            patternPivots.add(dPivot);

            GartleyPattern pattern = new GartleyPattern(
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

            xIdx = findLowestPivotIndexAfter(pivots, posAfterX);
            if (xIdx < 0) break;
            xPivot = pivots.get(xIdx);
            xPrice = xPivot.price();
        }

        return patterns;
    }

    /**
     * Check if Gartley is well-formed: highest_xb == a, lowest_ac == b, highest_from_b == c.
     * Also: x not bar's high, a not bar's low, b not bar's high, c not bar's low.
     */
    private boolean isWellFormed(BarSeries series, PivotPoint xPivot, PivotPoint aPivot, PivotPoint bPivot, PivotPoint cPivot) {
        Bar xBar = series.getBar(xPivot.barIndex());
        Bar aBar = series.getBar(aPivot.barIndex());
        Bar bBar = series.getBar(bPivot.barIndex());
        Bar cBar = series.getBar(cPivot.barIndex());

        if (Math.abs(xPivot.price() - xBar.getHighPrice().doubleValue()) < EPSILON) {
            return false;
        }

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

    private double findLowestCloseFromIndex(BarSeries series, int fromIndex, int toIndex) {
        double minClose = Double.POSITIVE_INFINITY;
        for (int i = fromIndex; i <= toIndex; i++) {
            Bar bar = series.getBar(i);
            minClose = Math.min(minClose, bar.getClosePrice().doubleValue());
        }
        return minClose;
    }

    private int findLowestPivotIndex(List<PivotPoint> pivots) {
        int minIdx = -1;
        double minPrice = Double.POSITIVE_INFINITY;

        for (int i = 0; i < pivots.size(); i++) {
            if (pivots.get(i).price() < minPrice) {
                minPrice = pivots.get(i).price();
                minIdx = i;
            }
        }

        return minIdx;
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
