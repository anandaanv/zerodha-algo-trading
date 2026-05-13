package com.dtech.ta.patterns.classic;

import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

/**
 * Rolling-mean True Range ATR utility (matches Python get_atr with rolling MEAN, not Wilder smoothing).
 * Reference: docs/spec-classic-patterns-port.md (ATR for Double Top/Bottom)
 */
public class ClassicAtr {

    private static final int DEFAULT_WINDOW = 15;

    /**
     * Compute rolling-mean True Range ending at barIndex over window bars.
     * TR = max(high-low, |high-prevClose|, |low-prevClose|)
     *
     * @param series the BarSeries
     * @param barIndex the bar index to compute ATR for
     * @param window the rolling window size (default 15)
     * @return the rolling-mean True Range
     * @throws IllegalArgumentException if not enough bars before barIndex
     */
    public static double mean(BarSeries series, int barIndex, int window) {
        // Need barIndex >= window - 1 to have window bars ending at barIndex
        if (barIndex < window - 1) {
            throw new IllegalArgumentException("not enough bars before barIndex for window size " + window);
        }

        double sum = 0.0;
        for (int i = barIndex - window + 1; i <= barIndex; i++) {
            double tr = trueRange(series, i);
            sum += tr;
        }

        return sum / window;
    }

    /**
     * Compute rolling-mean True Range with default window of 15.
     */
    public static double mean(BarSeries series, int barIndex) {
        return mean(series, barIndex, DEFAULT_WINDOW);
    }

    private static double trueRange(BarSeries series, int barIndex) {
        Bar bar = series.getBar(barIndex);
        double high = bar.getHighPrice().doubleValue();
        double low = bar.getLowPrice().doubleValue();
        double hl = high - low;

        if (barIndex == 0) {
            return hl;
        }

        Bar prevBar = series.getBar(barIndex - 1);
        double prevClose = prevBar.getClosePrice().doubleValue();
        double hpc = Math.abs(high - prevClose);
        double lpc = Math.abs(low - prevClose);

        return Math.max(hl, Math.max(hpc, lpc));
    }
}
