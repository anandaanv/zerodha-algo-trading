package com.dtech.ta.patterns.classic;

import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Detector for Bearish Flag patterns.
 * Low-pole + flag: new 7-day low exceeding 30-day and 90-day lows, followed by tight consolidation below Fib 50%.
 * Reference: docs/spec-classic-patterns-port.md (Bearish Flag section)
 */
public class BearishFlagClassicDetector implements ClassicPatternDetector<BearishFlagPattern> {

    private static final int MIN_SERIES_LENGTH = 50;
    private static final int RECENT_LOW_WINDOW = 7;
    private static final int MONTHLY_LOW_WINDOW = 30;
    private static final int QUARTERLY_LOW_WINDOW = 90;
    private static final int FLAG_MIN_BARS = 5;
    private static final double SMA_THRESHOLD = 1.08;

    @Override
    public Optional<BearishFlagPattern> findLatest(BarSeries series, List<PivotPoint> pivots, int lookbackBars) {
        List<BearishFlagPattern> all = findAll(series, pivots, lookbackBars);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }

    @Override
    public List<BearishFlagPattern> findAll(BarSeries series, List<PivotPoint> pivots, int lookbackBars) {
        List<BearishFlagPattern> patterns = new ArrayList<>();

        if (series.getBarCount() < MIN_SERIES_LENGTH) {
            return patterns;
        }

        if (pivots.isEmpty()) {
            return patterns;
        }

        int minEndBarIndex = Math.max(0, series.getEndIndex() - lookbackBars);
        int endBarIndex = series.getEndIndex();
        Bar endBar = series.getBar(endBarIndex);
        double currentClose = endBar.getClosePrice().doubleValue();

        // Find index of minimum Low in last 7 bars
        int recentLowIdx = findRecentLowIndex(series, RECENT_LOW_WINDOW);
        if (recentLowIdx < 0) {
            return patterns;
        }

        // Reject if last bar IS the recent low (still falling)
        if (recentLowIdx == endBarIndex) {
            return patterns;
        }

        // Reject if fewer than FLAG_MIN_BARS from low to current
        int barsSinceLow = endBarIndex - recentLowIdx;
        if (barsSinceLow < FLAG_MIN_BARS) {
            return patterns;
        }

        if (recentLowIdx < minEndBarIndex) {
            return patterns;
        }

        double recentLow = series.getBar(recentLowIdx).getLowPrice().doubleValue();

        // Find 30-day and 90-day lows
        double monthlyLow = findLowInWindow(series, MONTHLY_LOW_WINDOW);
        double quarterlyLow = findLowInWindow(series, QUARTERLY_LOW_WINDOW);

        // Recent low must be lower than both 30-day and 90-day lows
        if (recentLow > monthlyLow || recentLow > quarterlyLow) {
            return patterns;
        }

        // Compute SMA20 and SMA50
        double sma20 = Sma.value(series, endBarIndex, 20);
        double sma50 = Sma.value(series, endBarIndex, 50);

        if (Double.isNaN(sma20) || Double.isNaN(sma50)) {
            return patterns;
        }

        // Check downtrend regime: SMA20 < SMA50 / 1.08
        if (sma20 > sma50 / SMA_THRESHOLD) {
            return patterns;
        }

        // Get last pivot as anchor
        PivotPoint lastPivot = pivots.get(pivots.size() - 1);
        double lastPivotPrice = lastPivot.price();

        // Compute Fib 50% retracement (downside mirror)
        double fib50 = lastPivotPrice - (lastPivotPrice - recentLow) / 2.0;

        // Find maximum High from recentLowIdx to current
        double recentHigh = findHighInRange(series, recentLowIdx, endBarIndex);

        // Recent high must stay below Fib 50%
        if (recentHigh > fib50) {
            return patterns;
        }

        // Pattern is valid
        double avgBar = AvgBarLength.median(series, lastPivot.barIndex(), endBarIndex);

        List<PivotPoint> patternPivots = new ArrayList<>();
        patternPivots.add(lastPivot);
        patternPivots.add(PivotPoint.of(series, recentLowIdx, PivotType.LOW));

        BearishFlagPattern pattern = new BearishFlagPattern(
            Direction.BEARISH,
            patternPivots,
            lastPivot.barIndex(),
            lastPivotPrice,
            recentLowIdx,
            recentLow,
            endBarIndex,
            currentClose,
            lastPivot.barIndex(),
            endBarIndex,
            lastPivot.time(),
            endBar.getEndTime(),
            avgBar
        );

        patterns.add(pattern);
        return patterns;
    }

    private int findRecentLowIndex(BarSeries series, int windowSize) {
        int endIdx = series.getEndIndex();
        int startIdx = Math.max(0, endIdx - windowSize + 1);

        int minIdx = startIdx;
        double minLow = series.getBar(startIdx).getLowPrice().doubleValue();

        for (int i = startIdx + 1; i <= endIdx; i++) {
            double low = series.getBar(i).getLowPrice().doubleValue();
            if (low < minLow) {
                minLow = low;
                minIdx = i;
            }
        }

        return minIdx;
    }

    private double findLowInWindow(BarSeries series, int windowSize) {
        int endIdx = series.getEndIndex();
        int startIdx = Math.max(0, endIdx - windowSize + 1);

        double minLow = series.getBar(startIdx).getLowPrice().doubleValue();

        for (int i = startIdx + 1; i <= endIdx; i++) {
            double low = series.getBar(i).getLowPrice().doubleValue();
            if (low < minLow) {
                minLow = low;
            }
        }

        return minLow;
    }

    private double findHighInRange(BarSeries series, int fromIdx, int toIdx) {
        double maxHigh = series.getBar(fromIdx).getHighPrice().doubleValue();

        for (int i = fromIdx + 1; i <= toIdx; i++) {
            double high = series.getBar(i).getHighPrice().doubleValue();
            if (high > maxHigh) {
                maxHigh = high;
            }
        }

        return maxHigh;
    }
}
