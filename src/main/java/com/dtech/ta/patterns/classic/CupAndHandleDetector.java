package com.dtech.ta.patterns.classic;

import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Detector for Cup and Handle patterns (bullish reversal/continuation).
 * Walks 4-pivot windows (HIGH-LOW-HIGH-LOW) and validates cup/handle geometry.
 * Reference: docs/spec-classic-patterns-port.md (Cup and Handle section)
 */
public class CupAndHandleDetector implements ClassicPatternDetector<CupAndHandlePattern> {

    @Override
    public Optional<CupAndHandlePattern> findLatest(BarSeries series, List<PivotPoint> pivots, int lookbackBars) {
        List<CupAndHandlePattern> all = findAll(series, pivots, lookbackBars);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }

    @Override
    public List<CupAndHandlePattern> findAll(BarSeries series, List<PivotPoint> pivots, int lookbackBars) {
        List<CupAndHandlePattern> patterns = new ArrayList<>();
        int minEndBarIndex = Math.max(0, series.getEndIndex() - lookbackBars);

        if (pivots.size() < 4) {
            return patterns;
        }

        for (int i = 0; i <= pivots.size() - 4; i++) {
            List<PivotPoint> window = pivots.subList(i, i + 4);

            if (!isValidAlternation(window)) {
                continue;
            }

            PivotPoint a = window.get(0);
            PivotPoint b = window.get(1);
            PivotPoint c = window.get(2);
            PivotPoint d = window.get(3);

            if (d.barIndex() < minEndBarIndex) {
                continue;
            }

            double avgBar = AvgBarLength.median(series, a.barIndex(), d.barIndex());
            if (avgBar == 0) {
                continue;
            }

            if (test(a.price(), b.price(), c.price(), d.price(),
                     a.barIndex(), b.barIndex(), c.barIndex(), d.barIndex(), avgBar)) {
                CupAndHandlePattern pattern = new CupAndHandlePattern(
                    Direction.BULLISH,
                    window,
                    a.barIndex(),
                    d.barIndex(),
                    a.time(),
                    d.time(),
                    avgBar
                );
                patterns.add(pattern);
            }
        }

        return patterns;
    }

    /**
     * Test Cup and Handle geometry:
     *   - Rims (A and C) at roughly same level: |A - C| <= avgBarLength
     *   - Cup is deep: (A - B) > avgBarLength * 2
     *   - Cup is broad: both B-to-A bars and C-to-A bars >= 5 bars
     *   - Handle is shallow: |C - D| < (A - B) * 0.5
     */
    public static boolean test(double a, double b, double c, double d,
                               int aBar, int bBar, int cBar, int dBar,
                               double avgBarLength) {
        double rimDiff = Math.abs(a - c);
        double cupDepth = a - b;
        double handleDepth = Math.abs(c - d);

        int bToABars = Math.abs(aBar - bBar);
        int cToABars = Math.abs(cBar - aBar);

        return rimDiff <= avgBarLength
            && cupDepth > avgBarLength * 2
            && bToABars >= 5
            && cToABars >= 5
            && handleDepth < cupDepth * 0.5;
    }

    private static boolean isValidAlternation(List<PivotPoint> window) {
        if (window.size() != 4) {
            return false;
        }
        return window.get(0).type() == PivotType.HIGH
            && window.get(1).type() == PivotType.LOW
            && window.get(2).type() == PivotType.HIGH
            && window.get(3).type() == PivotType.LOW;
    }
}
