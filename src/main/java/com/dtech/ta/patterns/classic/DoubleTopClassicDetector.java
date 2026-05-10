package com.dtech.ta.patterns.classic;

import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Detector for Double Top patterns (bearish reversal).
 * Walks 4-pivot windows (HIGH-LOW-HIGH) plus current close, validates volume and ATR constraints.
 * Reference: docs/spec-classic-patterns-port.md (Double Top section)
 */
public class DoubleTopClassicDetector implements ClassicPatternDetector<DoubleTopPattern> {

    @Override
    public Optional<DoubleTopPattern> findLatest(BarSeries series, List<PivotPoint> pivots, int lookbackBars) {
        List<DoubleTopPattern> all = findAll(series, pivots, lookbackBars);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }

    @Override
    public List<DoubleTopPattern> findAll(BarSeries series, List<PivotPoint> pivots, int lookbackBars) {
        List<DoubleTopPattern> patterns = new ArrayList<>();
        int minEndBarIndex = Math.max(0, series.getEndIndex() - lookbackBars);

        if (pivots.size() < 4) {
            return patterns;
        }

        int cBarIndex = series.getEndIndex();
        double dPrice = series.getBar(cBarIndex).getClosePrice().doubleValue();

        for (int i = 0; i <= pivots.size() - 3; i++) {
            List<PivotPoint> window = pivots.subList(i, i + 3);

            if (!isValidAlternation(window)) {
                continue;
            }

            PivotPoint a = window.get(0);
            PivotPoint b = window.get(1);
            PivotPoint c = window.get(2);

            if (c.barIndex() < minEndBarIndex) {
                continue;
            }

            double avgBar = AvgBarLength.median(series, a.barIndex(), c.barIndex());
            if (avgBar == 0) {
                continue;
            }

            double aVol = series.getBar(a.barIndex()).getVolume().doubleValue();
            double cVol = series.getBar(c.barIndex()).getVolume().doubleValue();

            try {
                double atr = ClassicAtr.mean(series, c.barIndex(), 15);

                if (test(a.price(), b.price(), c.price(), dPrice, aVol, cVol, avgBar, atr)) {
                    List<PivotPoint> patternPivots = new ArrayList<>(window);
                    patternPivots.add(PivotPoint.of(series, cBarIndex, PivotType.LOW));

                    DoubleTopPattern pattern = new DoubleTopPattern(
                        Direction.BEARISH,
                        patternPivots,
                        aVol,
                        cVol,
                        atr,
                        a.barIndex(),
                        cBarIndex,
                        a.time(),
                        series.getBar(cBarIndex).getEndTime(),
                        avgBar
                    );
                    patterns.add(pattern);
                }
            } catch (IllegalArgumentException e) {
                // Not enough bars for ATR calculation
                continue;
            }
        }

        return patterns;
    }

    /**
     * Test Double Top geometry: peaks at similar level, declining volume, neckline constraints, ATR distance.
     */
    public static boolean test(double a, double b, double c, double d, double aVol, double cVol,
                                double avgBarLength, double atr) {
        return c - b < atr * 4
            && Math.abs(a - c) <= avgBarLength * 0.5
            && cVol < aVol
            && b < Math.min(a, c)
            && b < d && d < c;
    }

    private static boolean isValidAlternation(List<PivotPoint> window) {
        if (window.size() != 3) {
            return false;
        }
        return window.get(0).type() == PivotType.HIGH
            && window.get(1).type() == PivotType.LOW
            && window.get(2).type() == PivotType.HIGH;
    }
}
