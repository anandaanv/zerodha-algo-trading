package com.dtech.aitrader.v2.narrative.donchian;

import com.dtech.chartdata.model.OhlcBarDTO;

import java.time.Instant;
import java.util.List;

/**
 * Self-implemented Donchian Channels(period). Upper = max of last {@code period} highs; Lower =
 * min of last {@code period} lows; Middle = (Upper + Lower) / 2; Width = (Upper - Lower) / Middle
 * × 100.
 *
 * <p>Note: Donchian "look-back" classically EXCLUDES the current bar (the upper is the max of the
 * PRIOR {@code period} bars, used to detect a breakout when current close > prior-upper). For the
 * narrative engine we compute the inclusive channel (the visualized channel) so the "currently"
 * state is honest. Breakout events in {@link DonchianIndicatorConfig} use the prior-bar channel
 * for the breakout check.
 */
public final class DonchianComputer {

    private DonchianComputer() {}

    public static DonchianSeries compute(List<OhlcBarDTO> bars, int period,
                                          String symbol, String timeframe) {
        if (bars == null || bars.isEmpty()) {
            throw new IllegalArgumentException("bars must not be null or empty");
        }
        int n = bars.size();
        double[] upper = new double[n];
        double[] lower = new double[n];
        double[] middle = new double[n];
        double[] width = new double[n];
        double[] closes = new double[n];
        Instant[] timestamps = new Instant[n];

        for (int i = 0; i < n; i++) {
            int p = Math.min(period, i + 1);
            int start = i - p + 1;
            double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY;
            for (int j = start; j <= i; j++) {
                if (bars.get(j).getHigh() > hi) hi = bars.get(j).getHigh();
                if (bars.get(j).getLow() < lo) lo = bars.get(j).getLow();
            }
            upper[i] = hi;
            lower[i] = lo;
            middle[i] = (hi + lo) / 2.0;
            width[i] = middle[i] != 0 ? ((hi - lo) / middle[i]) * 100.0 : 0.0;
            closes[i] = bars.get(i).getClose();
            timestamps[i] = Instant.ofEpochSecond(bars.get(i).getTime());
        }
        return DonchianSeries.builder()
                .upper(upper).lower(lower).middle(middle).width(width).closes(closes)
                .barTimestamps(timestamps).symbol(symbol).timeframe(timeframe)
                .build();
    }
}
