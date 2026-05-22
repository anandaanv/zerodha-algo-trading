package com.dtech.aitrader.v2.narrative.ema;

import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.time.Instant;
import java.util.List;

/**
 * 4-tier EMA stack (20/50/100/200) via ta4j {@link EMAIndicator} — Standard EMA alpha (NOT Wilder).
 * Per delta Section 2: Wilder is only for RSI/ADX/ATR; EMA-stack uses standard EMA.
 */
public final class EmaComputer {

    private EmaComputer() {}

    public static EmaSeries compute(List<OhlcBarDTO> bars, int p20, int p50, int p100, int p200,
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
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        EMAIndicator e20 = new EMAIndicator(close, p20);
        EMAIndicator e50 = new EMAIndicator(close, p50);
        EMAIndicator e100 = new EMAIndicator(close, p100);
        EMAIndicator e200 = new EMAIndicator(close, p200);

        int n = bars.size();
        double[] a20 = new double[n], a50 = new double[n], a100 = new double[n], a200 = new double[n];
        double[] cl = new double[n];
        Instant[] timestamps = new Instant[n];
        for (int i = 0; i < n; i++) {
            a20[i] = e20.getValue(i).doubleValue();
            a50[i] = e50.getValue(i).doubleValue();
            a100[i] = e100.getValue(i).doubleValue();
            a200[i] = e200.getValue(i).doubleValue();
            cl[i] = bars.get(i).getClose();
            timestamps[i] = Instant.ofEpochSecond(bars.get(i).getTime());
        }
        // Warmup clamp — ta4j EMA seeds with the SMA of the first `period` bars; values before
        // bar `period` are mathematically valid but artificially flat. Find the first non-NaN
        // index across all four EMAs and clamp earlier bars to it. (For EMA200 this means the
        // first ~200 bars get the same value — fine for narrative purposes; the regime detector
        // skips early bars by definition.)
        int firstValid = -1;
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(a20[i]) && !Double.isNaN(a50[i])
                    && !Double.isNaN(a100[i]) && !Double.isNaN(a200[i])) {
                firstValid = i;
                break;
            }
        }
        if (firstValid > 0) {
            for (int i = 0; i < firstValid; i++) {
                a20[i] = a20[firstValid];
                a50[i] = a50[firstValid];
                a100[i] = a100[firstValid];
                a200[i] = a200[firstValid];
            }
        }
        return EmaSeries.builder()
                .ema20(a20).ema50(a50).ema100(a100).ema200(a200)
                .closes(cl)
                .barTimestamps(timestamps)
                .symbol(symbol).timeframe(timeframe)
                .build();
    }
}
