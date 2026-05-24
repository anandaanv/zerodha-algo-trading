package com.dtech.aitrader.v2.narrative.stoch;

import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.StochasticOscillatorDIndicator;
import org.ta4j.core.indicators.StochasticOscillatorKIndicator;

import java.time.Instant;
import java.util.List;

/**
 * Slow Stochastic (14,3,3) via ta4j. Per Lane's canonical default and the delta spec.
 *
 * <p>ta4j naming caveat (different from Lane's):
 * <ul>
 *   <li>{@code StochasticOscillatorKIndicator(barSeries, 14)} → Fast %K (raw)</li>
 *   <li>{@code StochasticOscillatorDIndicator(fastK)} → Fast %D = SMA(Fast %K, 3) — but this is
 *       what Lane calls Slow %K (the smoothed line)</li>
 *   <li>{@code SMAIndicator(slowK, 3)} → Slow %D = SMA(Slow %K, 3)</li>
 * </ul>
 *
 * <p>So this class maps ta4j idioms to Lane terminology: emitted stoch_k is Lane's Slow %K
 * (smoothed once); stoch_d is Lane's Slow %D (smoothed twice).
 */
public final class StochComputer {

    private StochComputer() {}

    public static StochSeries compute(List<OhlcBarDTO> bars, int kPeriod, int kSmoothing, int dSmoothing,
                                       String symbol, String timeframe) {
        if (bars == null || bars.isEmpty()) {
            throw new IllegalArgumentException("bars must not be null or empty");
        }
        BarSeries series = new BaseBarSeriesBuilder().withName(symbol).build();
        for (OhlcBarDTO b : bars) {
            Instant instant = Instant.ofEpochSecond(b.getTime());
            Bar taBar = BarsLoader.getBar(b.getOpen(), b.getHigh(), b.getLow(), b.getClose(),
                    b.getVolume(), instant);
            series.addBar(taBar);
        }

        StochasticOscillatorKIndicator fastK = new StochasticOscillatorKIndicator(series, kPeriod);
        // ta4j's StochasticOscillatorDIndicator is SMA(Fast %K, 3) — which is Lane's Slow %K.
        StochasticOscillatorDIndicator slowK = new StochasticOscillatorDIndicator(fastK);
        // Slow %D = SMA(Slow %K, 3)
        SMAIndicator slowD = new SMAIndicator(slowK, dSmoothing);

        int n = bars.size();
        double[] kArr = new double[n];
        double[] dArr = new double[n];
        Instant[] timestamps = new Instant[n];
        for (int i = 0; i < n; i++) {
            kArr[i] = slowK.getValue(i).doubleValue();
            dArr[i] = slowD.getValue(i).doubleValue();
            timestamps[i] = Instant.ofEpochSecond(bars.get(i).getTime());
        }
        // Warmup clamp — same approach as RsiComputer. Without it the chain (raw %K -> SMA-3 ->
        // SMA-3) emits inflated values for the first ~kPeriod+kSmoothing+dSmoothing-1 bars that
        // poison the adaptive-significance pivot engine's ATR.
        int warmup = kPeriod + kSmoothing + dSmoothing - 1;
        if (n > warmup) {
            double kStable = kArr[warmup];
            double dStable = dArr[warmup];
            for (int i = 0; i < warmup; i++) {
                kArr[i] = kStable;
                dArr[i] = dStable;
            }
        }
        return StochSeries.builder()
                .stochK(kArr)
                .stochD(dArr)
                .barTimestamps(timestamps)
                .symbol(symbol)
                .timeframe(timeframe)
                .build();
    }
}
