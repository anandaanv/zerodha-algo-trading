package com.dtech.ta.patterns.classic;

import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Detector for Bullish ABCD harmonic pattern.
 * 4 pivots: A (HIGH) -> B (LOW) -> C (HIGH) -> D (LOW, current).
 * C retracement must be in [0.382, 0.886].
 * Reference: docs/spec-classic-patterns-port.md (ABCD Bullish section)
 */
public class AbcdBullishDetector implements ClassicPatternDetector<AbcdPattern> {

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

        return searchBullish(series, pivots, minEndBarIndex, dBarIndex, dPrice, patterns);
    }

    /**
     * Search for bullish ABCD patterns using the A := C advance loop.
     */
    private List<AbcdPattern> searchBullish(BarSeries series, List<PivotPoint> pivots,
                                            int minEndBarIndex, int dBarIndex, double dPrice,
                                            List<AbcdPattern> patterns) {
        if (pivots.isEmpty()) {
            return patterns;
        }

        int aIdx = findHighestPivotIndex(pivots);
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

            int cIdx = findHighestPivotIndexAfter(pivots, posAfterA);
            if (cIdx < 0) {
                break;
            }

            PivotPoint cPivot = pivots.get(cIdx);
            double cPrice = cPivot.price();

            int bIdx = findLowestPivotIndexInRange(pivots, aIdx, cIdx);
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

            double cRetrace = FibRatios.snapToFib((cPrice - bPrice) / (aPrice - bPrice));

            if (!FibRatios.isInRange(cRetrace, 0.382, 0.886)) {
                aIdx = cIdx;
                aPivot = cPivot;
                aPrice = cPrice;
                continue;
            }

            double abDiff = aPrice - bPrice;
            double bcDiff = cPrice - bPrice;

            double abCdExt = cPrice - abDiff;
            double bc618Ext = cPrice - bcDiff * 1.618;
            double ab618Ext = cPrice - abDiff * 1.618;

            double lowestCloseFromC = findLowestCloseFromIndex(series, cPivot.barIndex(), dBarIndex);

            boolean isPerfect = Math.abs(cRetrace - 0.618) < EPSILON && abCdExt <= bc618Ext + EPSILON;
            boolean isAlternate = lowestCloseFromC < abCdExt - EPSILON;

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

            AbcdPattern pattern = new AbcdPattern(
                Direction.BULLISH,
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
     * Check if ABCD is well-formed: A not bar's low, B not bar's high, C not bar's low.
     * Also verify B is min in [A, C] and C is max after B.
     */
    private boolean isWellFormed(BarSeries series, PivotPoint aPivot, PivotPoint bPivot, PivotPoint cPivot) {
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

        if (cdCompletion > abCompletion * 2) {
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
