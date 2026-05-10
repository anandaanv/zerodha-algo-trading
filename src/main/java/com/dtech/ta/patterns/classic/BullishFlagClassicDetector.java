package com.dtech.ta.patterns.classic;

import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Detector for Bullish Flag patterns.
 * High-pole + flag: new 7-day high exceeding 30-day and 90-day highs, followed by tight consolidation above Fib 50%.
 * Reference: docs/spec-classic-patterns-port.md (Bullish Flag section)
 */
public class BullishFlagClassicDetector implements ClassicPatternDetector<BullishFlagPattern> {

    private static final int MIN_SERIES_LENGTH = 50;
    private static final int RECENT_HIGH_WINDOW = 7;
    private static final int MONTHLY_HIGH_WINDOW = 30;
    private static final int QUARTERLY_HIGH_WINDOW = 90;
    private static final int FLAG_MIN_BARS = 5;
    private static final double SMA_THRESHOLD = 1.08;

    @Override
    public Optional<BullishFlagPattern> findLatest(BarSeries series, List<PivotPoint> pivots, int lookbackBars) {
        List<BullishFlagPattern> all = findAll(series, pivots, lookbackBars);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }

    @Override
    public List<BullishFlagPattern> findAll(BarSeries series, List<PivotPoint> pivots, int lookbackBars) {
        List<BullishFlagPattern> patterns = new ArrayList<>();

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

        // Find index of maximum High in last 7 bars
        int recentHighIdx = findRecentHighIndex(series, RECENT_HIGH_WINDOW);
        if (recentHighIdx < 0) {
            return patterns;
        }

        // Reject if last bar IS the recent high (still rallying)
        if (recentHighIdx == endBarIndex) {
            return patterns;
        }

        // Reject if fewer than FLAG_MIN_BARS from high to current
        int barsSinceHigh = endBarIndex - recentHighIdx;
        if (barsSinceHigh < FLAG_MIN_BARS) {
            return patterns;
        }

        if (recentHighIdx < minEndBarIndex) {
            return patterns;
        }

        double recentHigh = series.getBar(recentHighIdx).getHighPrice().doubleValue();

        // Find 30-day and 90-day highs
        double monthlyHigh = findHighInWindow(series, MONTHLY_HIGH_WINDOW);
        double quarterlyHigh = findHighInWindow(series, QUARTERLY_HIGH_WINDOW);

        // Recent high must exceed both 30-day and 90-day highs
        if (recentHigh < monthlyHigh || recentHigh < quarterlyHigh) {
            return patterns;
        }

        // Compute SMA20 and SMA50
        double sma20 = Sma.value(series, endBarIndex, 20);
        double sma50 = Sma.value(series, endBarIndex, 50);

        if (Double.isNaN(sma20) || Double.isNaN(sma50)) {
            return patterns;
        }

        // Check uptrend regime: SMA20 > SMA50 * 1.08
        if (sma20 < sma50 * SMA_THRESHOLD) {
            return patterns;
        }

        // Get last pivot as anchor
        PivotPoint lastPivot = pivots.get(pivots.size() - 1);
        double lastPivotPrice = lastPivot.price();

        // Compute Fib 50% retracement
        double fib50 = lastPivotPrice + (recentHigh - lastPivotPrice) / 2.0;

        // Find minimum Low from recentHighIdx to current
        double recentLow = findLowInRange(series, recentHighIdx, endBarIndex);

        // Recent low must hold above Fib 50%
        if (recentLow < fib50) {
            return patterns;
        }

        // Pattern is valid
        double avgBar = AvgBarLength.median(series, lastPivot.barIndex(), endBarIndex);

        List<PivotPoint> patternPivots = new ArrayList<>();
        patternPivots.add(lastPivot);
        patternPivots.add(PivotPoint.of(series, recentHighIdx, PivotType.HIGH));

        BullishFlagPattern pattern = new BullishFlagPattern(
            Direction.BULLISH,
            patternPivots,
            lastPivot.barIndex(),
            lastPivotPrice,
            recentHighIdx,
            recentHigh,
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

    private int findRecentHighIndex(BarSeries series, int windowSize) {
        int endIdx = series.getEndIndex();
        int startIdx = Math.max(0, endIdx - windowSize + 1);

        int maxIdx = startIdx;
        double maxHigh = series.getBar(startIdx).getHighPrice().doubleValue();

        for (int i = startIdx + 1; i <= endIdx; i++) {
            double high = series.getBar(i).getHighPrice().doubleValue();
            if (high > maxHigh) {
                maxHigh = high;
                maxIdx = i;
            }
        }

        return maxIdx;
    }

    private double findHighInWindow(BarSeries series, int windowSize) {
        int endIdx = series.getEndIndex();
        int startIdx = Math.max(0, endIdx - windowSize + 1);

        double maxHigh = series.getBar(startIdx).getHighPrice().doubleValue();

        for (int i = startIdx + 1; i <= endIdx; i++) {
            double high = series.getBar(i).getHighPrice().doubleValue();
            if (high > maxHigh) {
                maxHigh = high;
            }
        }

        return maxHigh;
    }

    private double findLowInRange(BarSeries series, int fromIdx, int toIdx) {
        double minLow = series.getBar(fromIdx).getLowPrice().doubleValue();

        for (int i = fromIdx + 1; i <= toIdx; i++) {
            double low = series.getBar(i).getLowPrice().doubleValue();
            if (low < minLow) {
                minLow = low;
            }
        }

        return minLow;
    }
}
