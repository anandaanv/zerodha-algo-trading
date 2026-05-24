package com.dtech.aitrader.v2.narrative.roc;

import com.dtech.chartdata.model.OhlcBarDTO;

import java.time.Instant;
import java.util.List;

/**
 * Computes ROC(period) = 100 × (close[i] − close[i−period]) / close[i−period]. Self-implemented
 * to avoid ta4j version drift; the math is trivial.
 *
 * <p>Warmup: bars before {@code period} get 0.0 (ROC is undefined; clamped to neutral).
 */
public final class RocComputer {

    private RocComputer() {}

    public static RocSeries compute(List<OhlcBarDTO> bars, int period, String symbol, String timeframe) {
        if (bars == null || bars.isEmpty()) {
            throw new IllegalArgumentException("bars must not be null or empty");
        }
        int n = bars.size();
        double[] roc = new double[n];
        Instant[] timestamps = new Instant[n];
        for (int i = 0; i < n; i++) {
            timestamps[i] = Instant.ofEpochSecond(bars.get(i).getTime());
            if (i < period) {
                roc[i] = 0.0;
            } else {
                double prev = bars.get(i - period).getClose();
                double curr = bars.get(i).getClose();
                roc[i] = prev != 0 ? 100.0 * (curr - prev) / prev : 0.0;
            }
        }
        return RocSeries.builder()
                .roc(roc)
                .barTimestamps(timestamps)
                .symbol(symbol)
                .timeframe(timeframe)
                .build();
    }
}
