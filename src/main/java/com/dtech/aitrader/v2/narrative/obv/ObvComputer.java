package com.dtech.aitrader.v2.narrative.obv;

import com.dtech.chartdata.model.OhlcBarDTO;

import java.time.Instant;
import java.util.List;

/**
 * Computes OBV (On-Balance Volume) cumulatively:
 * <pre>
 *   OBV[0] = 0
 *   OBV[i] = OBV[i-1] + sign(close[i] - close[i-1]) × volume[i]
 * </pre>
 * Where sign is +1 if close rose, -1 if close fell, 0 if unchanged.
 *
 * <p>OBV's absolute value is scale-dependent and not comparable across instruments; pivots,
 * direction, and divergence-with-price are what matter — handled by the engine using OBV as a
 * pivot component.
 */
public final class ObvComputer {

    private ObvComputer() {}

    public static ObvSeries compute(List<OhlcBarDTO> bars, String symbol, String timeframe) {
        if (bars == null || bars.isEmpty()) {
            throw new IllegalArgumentException("bars must not be null or empty");
        }
        int n = bars.size();
        double[] obv = new double[n];
        Instant[] timestamps = new Instant[n];
        obv[0] = 0;
        timestamps[0] = Instant.ofEpochSecond(bars.get(0).getTime());
        for (int i = 1; i < n; i++) {
            double prevC = bars.get(i - 1).getClose();
            double currC = bars.get(i).getClose();
            double v = bars.get(i).getVolume();
            int sign = currC > prevC ? +1 : currC < prevC ? -1 : 0;
            obv[i] = obv[i - 1] + sign * v;
            timestamps[i] = Instant.ofEpochSecond(bars.get(i).getTime());
        }
        return ObvSeries.builder()
                .obv(obv)
                .barTimestamps(timestamps)
                .symbol(symbol)
                .timeframe(timeframe)
                .build();
    }
}
