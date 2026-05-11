package com.dtech.ta.patterns.classic;

import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Detector for Bearish ABCD harmonic pattern.
 * 4 pivots: A (LOW) -> B (HIGH) -> C (LOW) -> D (HIGH, current).
 * C retracement must be in [0.382, 0.886].
 * Reference: docs/spec-classic-patterns-port.md (ABCD Bearish section)
 */
public class AbcdBearishDetector implements ClassicPatternDetector<AbcdPattern> {

    private static final double EPSILON = 1e-6;

    @Override
    public Optional<AbcdPattern> findLatest(BarSeries series, List<PivotPoint> pivots, int lookbackBars) {
        List<AbcdPattern> all = findAll(series, pivots, lookbackBars);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }

    @Override
    public List<AbcdPattern> findAll(BarSeries series, List<PivotPoint> pivots, int lookbackBars) {
        List<AbcdPattern> patterns = new ArrayList<>();
        int minEndBarIndex = Math.max(0, series.getEndIndex() - lookbackBars);

        if (pivots.size() < 3) {
            return patterns;
        }

        int dBarIndex = series.getEndIndex();
        Bar dBar = series.getBar(dBarIndex);
        double dPrice = dBar.getClosePrice().doubleValue();

        return searchBearish(series, pivots, minEndBarIndex, dBarIndex, dPrice, patterns);
    }

    /**
     * Search for bearish ABCD patterns using the A := C advance loop.
     * Mirror of bullish: A is LOW, B is HIGH, C is LOW, D is HIGH.
     */
    private List<AbcdPattern> searchBearish(BarSeries series, List<PivotPoint> pivots,
                                            int minEndBarIndex, int dBarIndex, double dPrice,
                                            List<AbcdPattern> patterns) {
        if (pivots.isEmpty()) {
            return patterns;
        }

        int aIdx = findLowestPivotIndex(pivots);
        if (aIdx < 0) {
            return patterns;
        }

        PivotPoint aPivot = pivots.get(aIdx);
        double aPrice = aPivot.price();

        while (true) {
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
                aIdx = cIdx;
                aPivot = cPivot;
                aPrice = cPrice;
                continue;
            }

            PivotPoint bPivot = pivots.get(bIdx);
            double bPrice = bPivot.price();

            double avgBar = AvgBarLength.median(series, aPivot.barIndex(), cPivot.barIndex());

            if (avgBar == 0) {
                aIdx = cIdx;
                aPivot = cPivot;
                aPrice = cPrice;
                continue;
            }

            if (!isWellFormed(series, aPivot, bPivot, cPivot)) {
                aIdx = cIdx;
                aPivot = cPivot;
                aPrice = cPrice;
                continue;
            }

            double cRetrace = FibRatios.snapToFib((bPrice - cPrice) / (bPrice - aPrice));

            if (!FibRatios.isInRange(cRetrace, 0.382, 0.886)) {
                aIdx = cIdx;
                aPivot = cPivot;
                aPrice = cPrice;
                continue;
            }

            double abDiff = bPrice - aPrice;
            double bcDiff = bPrice - cPrice;

            double abCdExt = cPrice + abDiff;
            double bc618Ext = cPrice + bcDiff * 1.618;
            double ab618Ext = cPrice + abDiff * 1.618;

            double highestCloseFromC = findHighestCloseFromIndex(series, cPivot.barIndex(), dBarIndex);

            boolean isPerfect = Math.abs(cRetrace - 0.618) < EPSILON && abCdExt >= bc618Ext - EPSILON;
            boolean isAlternate = highestCloseFromC > abCdExt + EPSILON;

            double terminalPoint;
            if (isPerfect) {
                terminalPoint = abCdExt;
            } else if (isAlternate) {
                terminalPoint = ab618Ext;
            } else {
                terminalPoint = abCdExt;
            }

            int abCompletion = cPivot.barIndex() - aPivot.barIndex();
            int cdCompletion = dBarIndex - cPivot.barIndex();

            if (!isValidPattern(series, bPivot, dPrice, terminalPoint, cPivot.barIndex(), dBarIndex, abCompletion, cdCompletion)) {
                aIdx = cIdx;
                aPivot = cPivot;
                aPrice = cPrice;
                continue;
            }

            AbcdPattern.AbcdKind kind = isPerfect ? AbcdPattern.AbcdKind.PERFECT :
                                         isAlternate ? AbcdPattern.AbcdKind.ALTERNATE :
                                         AbcdPattern.AbcdKind.REGULAR;

            List<PivotPoint> patternPivots = new ArrayList<>();
            patternPivots.add(aPivot);
            patternPivots.add(bPivot);
            patternPivots.add(cPivot);
            // Add synthetic D pivot at current bar
            PivotPoint dPivot = new PivotPoint(dBarIndex, series.getBar(dBarIndex).getEndTime(), dPrice, PivotType.HIGH);
            patternPivots.add(dPivot);

            AbcdPattern pattern = new AbcdPattern(
                Direction.BEARISH,
                kind,
                patternPivots,
                cRetrace,
                terminalPoint,
                aPivot.barIndex(),
                dBarIndex,
                aPivot.time(),
                series.getBar(dBarIndex).getEndTime(),
                avgBar
            );
            patterns.add(pattern);

            aIdx = cIdx;
            aPivot = cPivot;
            aPrice = cPrice;
        }

        return patterns;
    }

    /**
     * Check if ABCD is well-formed: A not bar's high, B not bar's low, C not bar's high.
     */
    private boolean isWellFormed(BarSeries series, PivotPoint aPivot, PivotPoint bPivot, PivotPoint cPivot) {
        Bar aBar = series.getBar(aPivot.barIndex());
        Bar bBar = series.getBar(bPivot.barIndex());
        Bar cBar = series.getBar(cPivot.barIndex());

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

        if (cdCompletion > abCompletion * 2) {
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
