package com.dtech.ta.patterns.classic;

import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Detector for Triangle patterns (Ascending, Descending, Symmetric).
 * Walks 6-pivot windows (HIGH-LOW-HIGH-LOW-HIGH-LOW) and applies geometric tests.
 * Reference: docs/spec-classic-patterns-port.md (Triangle section)
 */
public class TriangleClassicDetector implements ClassicPatternDetector<TrianglePattern> {

    @Override
    public Optional<TrianglePattern> findLatest(BarSeries series, List<PivotPoint> pivots, int lookbackBars) {
        List<TrianglePattern> all = findAll(series, pivots, lookbackBars);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }

    @Override
    public List<TrianglePattern> findAll(BarSeries series, List<PivotPoint> pivots, int lookbackBars) {
        List<TrianglePattern> patterns = new ArrayList<>();
        int minEndBarIndex = Math.max(0, series.getEndIndex() - lookbackBars);

        if (pivots.size() < 6) {
            return patterns;
        }

        for (int i = 0; i <= pivots.size() - 6; i++) {
            List<PivotPoint> window = pivots.subList(i, i + 6);

            if (!isValidAlternation(window)) {
                continue;
            }

            PivotPoint a = window.get(0);
            PivotPoint b = window.get(1);
            PivotPoint c = window.get(2);
            PivotPoint d = window.get(3);
            PivotPoint e = window.get(4);
            PivotPoint f = window.get(5);

            if (f.barIndex() < minEndBarIndex) {
                continue;
            }

            double avgBar = AvgBarLength.median(series, a.barIndex(), f.barIndex());
            if (avgBar == 0) {
                continue;
            }

            Optional<TrianglePattern.TriangleKind> kind = classify(
                a.price(), b.price(), c.price(), d.price(), e.price(), f.price(), avgBar
            );

            if (kind.isPresent()) {
                Direction direction = determineDirection(kind.get(), a.price(), c.price(), e.price());
                TrianglePattern pattern = new TrianglePattern(
                    direction,
                    kind.get(),
                    window,
                    a.barIndex(),
                    f.barIndex(),
                    a.time(),
                    f.time(),
                    avgBar
                );
                patterns.add(pattern);
            }
        }

        return patterns;
    }

    /**
     * Classify a 6-pivot triangle candidate into ASCENDING, DESCENDING, or SYMMETRIC.
     * Returns Optional.empty() if none match.
     */
    public static Optional<TrianglePattern.TriangleKind> classify(
        double a, double b, double c, double d, double e, double f, double avgBarLength
    ) {
        boolean isAcStraightLine = Math.abs(a - c) <= avgBarLength;
        boolean isCeStraightLine = Math.abs(c - e) <= avgBarLength;

        if (isAcStraightLine && isCeStraightLine && b < d && d < f && f < e) {
            return Optional.of(TrianglePattern.TriangleKind.ASCENDING);
        }

        boolean isBdStraightLine = Math.abs(b - d) <= avgBarLength;

        if (isBdStraightLine && a > c && c > e && e > f && f >= d) {
            return Optional.of(TrianglePattern.TriangleKind.DESCENDING);
        }

        if (a > c && c > e && b < d && d < f && e > f) {
            return Optional.of(TrianglePattern.TriangleKind.SYMMETRIC);
        }

        return Optional.empty();
    }

    private static boolean isValidAlternation(List<PivotPoint> window) {
        if (window.size() != 6) {
            return false;
        }
        return window.get(0).type() == PivotType.HIGH
            && window.get(1).type() == PivotType.LOW
            && window.get(2).type() == PivotType.HIGH
            && window.get(3).type() == PivotType.LOW
            && window.get(4).type() == PivotType.HIGH
            && window.get(5).type() == PivotType.LOW;
    }

    private static Direction determineDirection(TrianglePattern.TriangleKind kind, double a, double c, double e) {
        if (kind == TrianglePattern.TriangleKind.ASCENDING) {
            return Direction.BULLISH;
        }
        if (kind == TrianglePattern.TriangleKind.DESCENDING) {
            return Direction.BEARISH;
        }
        // Symmetric: default to BULLISH per spec note
        return Direction.BULLISH;
    }
}
