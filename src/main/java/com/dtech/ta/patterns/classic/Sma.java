package com.dtech.ta.patterns.classic;

import org.ta4j.core.BarSeries;

/**
 * Simple Moving Average (SMA) utility.
 * Computes rolling mean of close prices over a specified period.
 */
public final class Sma {

    private Sma() {}

    /**
     * Compute SMA at a given bar index.
     * @param series BarSeries to compute SMA from
     * @param barIndex the current bar index
     * @param period the SMA period (e.g., 20 for SMA20)
     * @return the SMA value, or NaN if insufficient bars
     */
    public static double value(BarSeries series, int barIndex, int period) {
        if (barIndex < period - 1) {
            return Double.NaN;
        }

        double sum = 0.0;
        for (int i = barIndex - period + 1; i <= barIndex; i++) {
            sum += series.getBar(i).getClosePrice().doubleValue();
        }
        return sum / period;
    }
}
