package com.dtech.ta.patterns.classic;

import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Detector for Bearish Volatility Contraction Pattern.
 * Walks pivot windows (LOW-HIGH-LOW-HIGH) + current close, validates price/volatility geometry.
 * Reference: docs/spec-vcp-pattern-port.md (VCP detection algorithm)
 */
public class BearishVcpDetector implements ClassicPatternDetector<BearishVcpPattern> {

    @Override
    public Optional<BearishVcpPattern> findLatest(BarSeries series, List<PivotPoint> pivots, int lookbackBars) {
        List<BearishVcpPattern> all = findAll(series, pivots, lookbackBars);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }

    @Override
    public List<BearishVcpPattern> findAll(BarSeries series, List<PivotPoint> pivots, int lookbackBars) {
        List<BearishVcpPattern> patterns = new ArrayList<>();
        int minEndBarIndex = Math.max(0, series.getEndIndex() - lookbackBars);

        if (pivots.size() < 4) {
            return patterns;
        }

        int eBarIndex = series.getEndIndex();
        Bar eBar = series.getBar(eBarIndex);
        double ePrice = eBar.getClosePrice().doubleValue();

        return searchBearish(series, pivots, minEndBarIndex, eBarIndex, ePrice, patterns);
    }

    /**
     * Search for bearish VCP patterns using the iterative A := C loop algorithm.
     * See Python reference: find_bearish_vcp() lines 686-779.
     */
    private List<BearishVcpPattern> searchBearish(BarSeries series, List<PivotPoint> pivots,
                                                   int minEndBarIndex, int eBarIndex, double ePrice,
                                                   List<BearishVcpPattern> patterns) {
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

            int bIdx = findHighestPivotIndexAfter(pivots, posAfterA);
            if (bIdx < 0) {
                break;
            }

            PivotPoint bPivot = pivots.get(bIdx);
            double bPrice = bPivot.price();

            int posAfterB = bIdx + 1;

            if (posAfterB >= pivots.size()) {
                break;
            }

            int dIdx = findHighestPivotIndexAfter(pivots, posAfterB);
            if (dIdx < 0) {
                break;
            }

            PivotPoint dPivot = pivots.get(dIdx);
            double dPrice = dPivot.price();

            int cIdx = findLowestPivotIndexInRange(pivots, bIdx, dIdx);
            if (cIdx < 0) {
                break;
            }

            PivotPoint cPivot = pivots.get(cIdx);
            double cPrice = cPivot.price();

            double avgBar = AvgBarLength.median(series, aPivot.barIndex(), cPivot.barIndex());

            if (avgBar == 0) {
                aIdx = cIdx;
                aPrice = cPrice;
                aPivot = cPivot;
                continue;
            }

            if (test(aPrice, bPrice, cPrice, dPrice, ePrice, avgBar)) {
                if (!isIntegrityViolated(series, cPivot.barIndex(), cPrice, dPivot.barIndex(), dPrice, eBarIndex)) {
                    List<PivotPoint> patternPivots = new ArrayList<>();
                    patternPivots.add(aPivot);
                    patternPivots.add(bPivot);
                    patternPivots.add(cPivot);
                    patternPivots.add(dPivot);

                    BearishVcpPattern pattern = new BearishVcpPattern(
                        Direction.BEARISH,
                        patternPivots,
                        eBarIndex,
                        ePrice,
                        aPivot.barIndex(),
                        eBarIndex,
                        aPivot.time(),
                        series.getBar(eBarIndex).getEndTime(),
                        avgBar
                    );
                    patterns.add(pattern);
                    break;
                }
            }

            aIdx = cIdx;
            aPrice = cPrice;
            aPivot = cPivot;
        }

        return patterns;
    }

    /**
     * Test bearish VCP geometry: A and C near each other, B and D show contraction, E not yet broken down.
     * Mirrors is_bearish_vcp() logic from Python source.
     */
    public static boolean test(double a, double b, double c, double d, double e, double avgBarLength) {
        if (avgBarLength <= 0) {
            return false;
        }

        if (c < a && Math.abs(a - c) >= avgBarLength * 0.5) {
            return false;
        }

        return Math.abs(a - c) <= avgBarLength
            && Math.abs(b - d) >= avgBarLength * 0.8
            && b > Math.max(a, Math.max(c, Math.max(d, e)))
            && d > Math.max(a, Math.max(c, e))
            && e > c;
    }

    /**
     * Check if pattern integrity is violated: C support breached or D resistance breached after formation.
     * Bearish: no close below C after C-index, no close above D after D-index.
     */
    private boolean isIntegrityViolated(BarSeries series, int cBarIndex, double cPrice,
                                        int dBarIndex, double dPrice, int eBarIndex) {
        for (int i = cBarIndex + 1; i <= eBarIndex; i++) {
            Bar bar = series.getBar(i);
            if (bar.getClosePrice().doubleValue() < cPrice) {
                return true;
            }
        }

        for (int i = dBarIndex + 1; i <= eBarIndex; i++) {
            Bar bar = series.getBar(i);
            if (bar.getClosePrice().doubleValue() > dPrice) {
                return true;
            }
        }

        return false;
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
