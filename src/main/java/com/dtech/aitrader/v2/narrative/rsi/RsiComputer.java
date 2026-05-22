package com.dtech.aitrader.v2.narrative.rsi;

import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.time.Instant;
import java.util.List;

/**
 * Computes RSI from OHLC bars using ta4j's {@link RSIIndicator} (Wilder smoothing).
 *
 * <p>Per the user's standing rule "use ta4j wherever possible / use everything battle-tested" — we
 * delegate to ta4j for the actual RSI math rather than re-implementing.
 */
public final class RsiComputer {

    private RsiComputer() {}

    public static RsiSeries compute(List<OhlcBarDTO> bars, int period, String symbol, String timeframe) {
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

        RSIIndicator rsiIndicator = new RSIIndicator(new ClosePriceIndicator(series), period);
        int n = bars.size();
        double[] rsi = new double[n];
        Instant[] timestamps = new Instant[n];
        for (int i = 0; i < n; i++) {
            rsi[i] = rsiIndicator.getValue(i).doubleValue();
            timestamps[i] = Instant.ofEpochSecond(bars.get(i).getTime());
        }
        // Smooth the ta4j warmup transient: ta4j returns 0 for the first up/down bars then
        // suddenly snaps to a real value (~50) at bar `period`. That artificial spike kills the
        // adaptive-significance pivot engine's ATR for the rest of the series. Clamp the warmup
        // window to the first stable value so pivot detection sees a clean signal.
        if (n > period) {
            double firstStable = rsi[period];
            for (int i = 0; i < period; i++) {
                rsi[i] = firstStable;
            }
        }
        return RsiSeries.builder()
                .rsi(rsi)
                .barTimestamps(timestamps)
                .symbol(symbol)
                .timeframe(timeframe)
                .build();
    }
}
