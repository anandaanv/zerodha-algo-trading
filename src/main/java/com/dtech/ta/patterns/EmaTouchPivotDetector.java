package com.dtech.ta.patterns;

import com.dtech.chartpattern.zigzag.ZigZagPoint;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.util.ArrayList;
import java.util.List;

/**
 * EMA-touch-based pivot detector. Identifies local extremes where price
 * touches or bounces from EMA100/200 within an ATR band — the structural
 * "EMA-anchored pivot" hypothesis.
 *
 * Bypasses ZigZag (which over-filters intraday EMA touches on lower TFs).
 *
 * Pivot rules:
 *   - LOCAL HIGH at bar i: bar[i].high is max in [i-window, i+window]
 *     AND |bar[i].high - ema100[i]| ≤ zoneAtr × atr[i]  OR  same for ema200
 *   - LOCAL LOW: mirrored on bar[i].low
 *
 * Two pivots cannot share the same bar; use the type whose extreme is closer to EMA.
 */
public class EmaTouchPivotDetector {

    private static final int DEFAULT_EMA_FAST = 100;
    private static final int DEFAULT_EMA_SLOW = 200;

    private final BarSeries series;
    private final double[] atrValues;
    private final int window;
    private final double zoneAtr;
    private final int emaFastPeriod;
    private final int emaSlowPeriod;

    private final double[] ema100;
    private final double[] ema200;

    public EmaTouchPivotDetector(BarSeries series, double[] atrValues, int window, double zoneAtr) {
        this(series, atrValues, window, zoneAtr, DEFAULT_EMA_FAST, DEFAULT_EMA_SLOW);
    }

    public EmaTouchPivotDetector(BarSeries series, double[] atrValues, int window, double zoneAtr,
                                  int emaFastPeriod, int emaSlowPeriod) {
        this.series = series;
        this.atrValues = atrValues;
        this.window = window;
        this.zoneAtr = zoneAtr;
        this.emaFastPeriod = emaFastPeriod;
        this.emaSlowPeriod = emaSlowPeriod;

        ClosePriceIndicator close = new ClosePriceIndicator(series);
        EMAIndicator e1 = new EMAIndicator(close, emaFastPeriod);
        EMAIndicator e2 = new EMAIndicator(close, emaSlowPeriod);
        int n = series.getBarCount();
        this.ema100 = new double[n];
        this.ema200 = new double[n];
        for (int i = 0; i < n; i++) {
            this.ema100[i] = e1.getValue(i).doubleValue();
            this.ema200[i] = e2.getValue(i).doubleValue();
        }
    }

    /**
     * Default: window=10 bars, zone=1.0×ATR
     */
    public EmaTouchPivotDetector(BarSeries series, double[] atrValues) {
        this(series, atrValues, 10, 1.0);
    }

    public List<ZigZagPoint> detect() {
        List<ZigZagPoint> pivots = new ArrayList<>();
        int n = series.getBarCount();
        if (n < 2 * window + emaSlowPeriod) return pivots;

        // Start scan after EMA200 has warmed up
        for (int i = Math.max(emaSlowPeriod, window); i < n - window; i++) {
            Bar b = series.getBar(i);
            double high = b.getHighPrice().doubleValue();
            double low = b.getLowPrice().doubleValue();
            double atr = atrValues[i];
            if (atr <= 0) continue;

            boolean isLocalHigh = true, isLocalLow = true;
            for (int j = i - window; j <= i + window; j++) {
                if (j == i) continue;
                Bar nb = series.getBar(j);
                if (nb.getHighPrice().doubleValue() > high) isLocalHigh = false;
                if (nb.getLowPrice().doubleValue() < low)  isLocalLow = false;
                if (!isLocalHigh && !isLocalLow) break;
            }

            // Distance from EMAs
            double distHighE100 = Math.abs(high - ema100[i]);
            double distHighE200 = Math.abs(high - ema200[i]);
            double distLowE100  = Math.abs(low  - ema100[i]);
            double distLowE200  = Math.abs(low  - ema200[i]);
            double tol = zoneAtr * atr;

            boolean highInZone = distHighE100 <= tol || distHighE200 <= tol;
            boolean lowInZone  = distLowE100  <= tol || distLowE200  <= tol;

            // If both qualify on same bar (rare), emit the one closer to its EMA
            if (isLocalHigh && highInZone && isLocalLow && lowInZone) {
                double minHigh = Math.min(distHighE100, distHighE200);
                double minLow  = Math.min(distLowE100,  distLowE200);
                if (minHigh <= minLow) {
                    pivots.add(toPivot(i, high, ZigZagPoint.Type.HIGH, b));
                } else {
                    pivots.add(toPivot(i, low, ZigZagPoint.Type.LOW, b));
                }
            } else if (isLocalHigh && highInZone) {
                pivots.add(toPivot(i, high, ZigZagPoint.Type.HIGH, b));
            } else if (isLocalLow && lowInZone) {
                pivots.add(toPivot(i, low, ZigZagPoint.Type.LOW, b));
            }
        }
        return pivots;
    }

    private ZigZagPoint toPivot(int barIndex, double value, ZigZagPoint.Type type, Bar bar) {
        return ZigZagPoint.builder()
                .barIndex(barIndex)
                .timestamp(bar.getEndTime())
                .value(value)
                .type(type)
                .build();
    }
}
