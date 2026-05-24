package com.dtech.aitrader.v2.narrative.vwap;

import com.dtech.chartdata.model.OhlcBarDTO;

import java.time.Instant;
import java.util.List;

/**
 * Rolling VWAP over the configured period. typical price = (H+L+C)/3, weighted by volume.
 * Warmup before the first {@code period} bars uses an expanding-window VWAP so we still get
 * a usable value (no NaN tail).
 *
 * <p>Self-implemented (not via ta4j) to keep the computation explicit and to handle zero-volume
 * bars defensively (degenerate to typical-price-only). For the v2 SNAPSHOT-tier emission this is
 * fine; session-anchored AVWAP can be added later.
 */
public final class VwapComputer {

    private VwapComputer() {}

    public static VwapSeries compute(List<OhlcBarDTO> bars, int period, String symbol, String timeframe) {
        if (bars == null || bars.isEmpty()) {
            throw new IllegalArgumentException("bars must not be null or empty");
        }
        int n = bars.size();
        double[] vwap = new double[n];
        double[] closes = new double[n];
        Instant[] timestamps = new Instant[n];

        double[] tpVol = new double[n];   // typical-price × volume
        double[] vol = new double[n];     // volume
        for (int i = 0; i < n; i++) {
            OhlcBarDTO b = bars.get(i);
            double tp = (b.getHigh() + b.getLow() + b.getClose()) / 3.0;
            double v = Math.max(b.getVolume(), 0.0);
            tpVol[i] = tp * v;
            vol[i] = v;
            closes[i] = b.getClose();
            timestamps[i] = Instant.ofEpochSecond(b.getTime());
        }

        for (int i = 0; i < n; i++) {
            int start = Math.max(0, i - period + 1);
            double sumTpVol = 0, sumVol = 0;
            for (int j = start; j <= i; j++) {
                sumTpVol += tpVol[j];
                sumVol += vol[j];
            }
            if (sumVol > 0) {
                vwap[i] = sumTpVol / sumVol;
            } else {
                // Zero-volume window — fall back to typical-price average.
                double sumTp = 0;
                int count = 0;
                for (int j = start; j <= i; j++) {
                    OhlcBarDTO b = bars.get(j);
                    sumTp += (b.getHigh() + b.getLow() + b.getClose()) / 3.0;
                    count++;
                }
                vwap[i] = count > 0 ? sumTp / count : closes[i];
            }
        }
        return VwapSeries.builder()
                .vwap(vwap)
                .closes(closes)
                .barTimestamps(timestamps)
                .symbol(symbol)
                .timeframe(timeframe)
                .build();
    }
}
