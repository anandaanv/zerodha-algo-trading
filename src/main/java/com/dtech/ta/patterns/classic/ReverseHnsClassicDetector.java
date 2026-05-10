package com.dtech.ta.patterns.classic;

import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Detector for Reverse Head and Shoulders patterns (bullish reversal).
 * Walks 5-pivot windows (LOW-HIGH-LOW-HIGH-LOW) and validates neckline with current close.
 * Reference: docs/spec-classic-patterns-port.md (Reverse H&S section)
 */
public class ReverseHnsClassicDetector implements ClassicPatternDetector<ReverseHnsPattern> {

    @Override
    public Optional<ReverseHnsPattern> findLatest(BarSeries series, List<PivotPoint> pivots, int lookbackBars) {
        List<ReverseHnsPattern> all = findAll(series, pivots, lookbackBars);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }

    @Override
    public List<ReverseHnsPattern> findAll(BarSeries series, List<PivotPoint> pivots, int lookbackBars) {
        List<ReverseHnsPattern> patterns = new ArrayList<>();
        int minEndBarIndex = Math.max(0, series.getEndIndex() - lookbackBars);

        if (pivots.size() < 5) {
            return patterns;
        }

        double fPrice = series.getBar(series.getEndIndex()).getClosePrice().doubleValue();
        int fBarIndex = series.getEndIndex();

        for (int i = 0; i <= pivots.size() - 5; i++) {
            List<PivotPoint> window = pivots.subList(i, i + 5);

            if (!isValidAlternation(window)) {
                continue;
            }

            PivotPoint a = window.get(0);
            PivotPoint b = window.get(1);
            PivotPoint c = window.get(2);
            PivotPoint d = window.get(3);
            PivotPoint e = window.get(4);

            if (e.barIndex() < minEndBarIndex) {
                continue;
            }

            double avgBar = AvgBarLength.median(series, a.barIndex(), e.barIndex());
            if (avgBar == 0) {
                continue;
            }

            if (test(a.price(), b.price(), c.price(), d.price(), e.price(), fPrice, avgBar)) {
                ReverseHnsPattern pattern = new ReverseHnsPattern(
                    Direction.BULLISH,
                    window,
                    fBarIndex,
                    fPrice,
                    a.barIndex(),
                    e.barIndex(),
                    a.time(),
                    e.time(),
                    avgBar
                );
                patterns.add(pattern);
            }
        }

        return patterns;
    }

    /**
     * Test Reverse HNS geometry: inverse head lower than shoulders, neckline above both, close above right shoulder.
     */
    public static boolean test(double a, double b, double c, double d, double e, double f, double avgBarLength) {
        double shoulderHeightThreshold = Math.round(avgBarLength * 0.6 * 100.0) / 100.0;

        return c < Math.min(a, e)
            && Math.min(b, d) > Math.max(a, e)
            && f > e
            && Math.abs(b - d) < avgBarLength
            && Math.abs(c - e) > shoulderHeightThreshold;
    }

    private static boolean isValidAlternation(List<PivotPoint> window) {
        if (window.size() != 5) {
            return false;
        }
        return window.get(0).type() == PivotType.LOW
            && window.get(1).type() == PivotType.HIGH
            && window.get(2).type() == PivotType.LOW
            && window.get(3).type() == PivotType.HIGH
            && window.get(4).type() == PivotType.LOW;
    }
}
