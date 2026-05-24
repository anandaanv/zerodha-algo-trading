package com.dtech.aitrader.v2.narrative.aroon;

import com.dtech.chartdata.model.OhlcBarDTO;

import java.time.Instant;
import java.util.List;

/**
 * Self-implemented Aroon(period). For each bar i, look back {@code period} bars (inclusive),
 * find the index of the highest high (h_idx) and lowest low (l_idx). Then:
 *
 * <pre>
 *   Aroon-up   = 100 × (period − (i − h_idx)) / period
 *   Aroon-down = 100 × (period − (i − l_idx)) / period
 *   Aroon-osc  = Aroon-up − Aroon-down
 * </pre>
 *
 * <p>Warmup (i &lt; period): use the partial window (i+1 bars back), expanding to full at i=period.
 */
public final class AroonComputer {

    private AroonComputer() {}

    public static AroonSeries compute(List<OhlcBarDTO> bars, int period, String symbol, String timeframe) {
        if (bars == null || bars.isEmpty()) {
            throw new IllegalArgumentException("bars must not be null or empty");
        }
        int n = bars.size();
        double[] up = new double[n];
        double[] down = new double[n];
        double[] osc = new double[n];
        Instant[] timestamps = new Instant[n];

        for (int i = 0; i < n; i++) {
            int p = Math.min(period, i + 1);
            int start = i - p + 1;
            double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY;
            int hiIdx = start, loIdx = start;
            for (int j = start; j <= i; j++) {
                double h = bars.get(j).getHigh();
                double l = bars.get(j).getLow();
                if (h > hi) { hi = h; hiIdx = j; }
                if (l < lo) { lo = l; loIdx = j; }
            }
            up[i] = 100.0 * (p - (i - hiIdx)) / p;
            down[i] = 100.0 * (p - (i - loIdx)) / p;
            osc[i] = up[i] - down[i];
            timestamps[i] = Instant.ofEpochSecond(bars.get(i).getTime());
        }
        return AroonSeries.builder()
                .aroonUp(up).aroonDown(down).aroonOsc(osc)
                .barTimestamps(timestamps).symbol(symbol).timeframe(timeframe)
                .build();
    }
}
