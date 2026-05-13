package com.dtech.ta.patterns.classic;

import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Detector for Uptrend Line patterns (support lines).
 * Connects ascending LOW pivots such that price does not breach below the line.
 * Direction: BULLISH (support line).
 * Reference: docs/spec-classic-patterns-port.md (Trendline section)
 */
public class UptrendLineDetector implements ClassicPatternDetector<TrendlinePattern> {

    private static final double TOUCHPOINT_TOLERANCE_RATIO = 0.3;
    private static final double BREACH_TOLERANCE_RATIO = 0.5;
    private static final int MIN_TOUCHPOINT_COUNT = 3;
    private static final int MIN_SPAN_BARS = 20;

    @Override
    public Optional<TrendlinePattern> findLatest(BarSeries series, List<PivotPoint> pivots, int lookbackBars) {
        List<TrendlinePattern> all = findAll(series, pivots, lookbackBars);
        if (all.isEmpty()) {
            return Optional.empty();
        }
        // Return the one with the most recent endBarIndex
        return Optional.of(all.stream()
            .max((a, b) -> Integer.compare(a.endBarIndex(), b.endBarIndex()))
            .orElse(all.get(all.size() - 1)));
    }

    @Override
    public List<TrendlinePattern> findAll(BarSeries series, List<PivotPoint> pivots, int lookbackBars) {
        List<TrendlinePattern> patterns = new ArrayList<>();

        if (pivots.size() < 2) {
            return patterns;
        }

        // Filter to LOW pivots only and in recent lookback
        List<PivotPoint> lowPivots = new ArrayList<>();
        int minEndBarIndex = Math.max(0, series.getEndIndex() - lookbackBars);

        for (PivotPoint p : pivots) {
            if (p.type() == PivotType.LOW && p.barIndex() >= minEndBarIndex) {
                lowPivots.add(p);
            }
        }

        if (lowPivots.size() < 2) {
            return patterns;
        }

        // Try all pairs of LOW pivots
        for (int i = 0; i < lowPivots.size() - 1; i++) {
            for (int j = i + 1; j < lowPivots.size(); j++) {
                PivotPoint a = lowPivots.get(i);
                PivotPoint b = lowPivots.get(j);

                // Uptrend: second pivot must be higher than first
                if (b.price() <= a.price()) {
                    continue;
                }

                // Require minimum span between anchors
                if (b.barIndex() - a.barIndex() < MIN_SPAN_BARS) {
                    continue;
                }

                double slope = (b.price() - a.price()) / (double) (b.barIndex() - a.barIndex());
                double yIntercept = a.price() - slope * a.barIndex();

                Line line = new Line(slope, yIntercept, a.barIndex(), a.price(), series.getEndIndex(),
                    slope * series.getEndIndex() + yIntercept);

                // Count touchpoints in range [a, b]
                int touchpointCount = 0;
                double avgBar = AvgBarLength.median(series, a.barIndex(), b.barIndex());

                if (avgBar == 0) {
                    continue;
                }

                double touchTolerance = avgBar * TOUCHPOINT_TOLERANCE_RATIO;
                double breachTolerance = avgBar * BREACH_TOLERANCE_RATIO;
                boolean breached = false;

                for (int barIdx = a.barIndex(); barIdx <= b.barIndex(); barIdx++) {
                    double barLow = series.getBar(barIdx).getLowPrice().doubleValue();
                    double lineValue = line.valueAt(barIdx);
                    double distanceBelow = lineValue - barLow;

                    // Check for significant breach
                    if (distanceBelow > breachTolerance) {
                        breached = true;
                        break;
                    }

                    // Count touchpoint
                    if (Math.abs(distanceBelow) <= touchTolerance) {
                        touchpointCount++;
                    }
                }

                if (breached || touchpointCount < MIN_TOUCHPOINT_COUNT) {
                    continue;
                }

                // Valid trendline found
                List<PivotPoint> patternPivots = new ArrayList<>();
                patternPivots.add(a);
                patternPivots.add(b);

                Bar startBar = series.getBar(a.barIndex());
                Bar endBar = series.getBar(series.getEndIndex());

                TrendlinePattern pattern = new TrendlinePattern(
                    Direction.BULLISH,
                    line,
                    patternPivots,
                    touchpointCount,
                    a.barIndex(),
                    series.getEndIndex(),
                    startBar.getEndTime(),
                    endBar.getEndTime(),
                    avgBar
                );

                patterns.add(pattern);
            }
        }

        return patterns;
    }
}
