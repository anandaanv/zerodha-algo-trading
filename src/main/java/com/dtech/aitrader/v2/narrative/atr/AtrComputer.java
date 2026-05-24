package com.dtech.aitrader.v2.narrative.atr;

import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.ATRIndicator;

import java.time.Instant;
import java.util.List;

/**
 * Computes ATR(14) via ta4j (Wilder smoothing — owner guidance: WILDER for ATR + ADX, standard EMA
 * elsewhere). Same warmup-clamp pattern as ADX/RSI: dynamic first-non-NaN detection, clamp the
 * pre-stable bars to the first stable value.
 */
public final class AtrComputer {

    private AtrComputer() {}

    public static AtrSeries compute(List<OhlcBarDTO> bars, int period, String symbol, String timeframe) {
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
        ATRIndicator atrI = new ATRIndicator(series, period);

        int n = bars.size();
        double[] atrArr = new double[n];
        Instant[] timestamps = new Instant[n];
        for (int i = 0; i < n; i++) {
            atrArr[i] = atrI.getValue(i).doubleValue();
            timestamps[i] = Instant.ofEpochSecond(bars.get(i).getTime());
        }

        int firstValid = -1;
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(atrArr[i]) && atrArr[i] > 0) {
                firstValid = i;
                break;
            }
        }
        if (firstValid > 0) {
            double v = atrArr[firstValid];
            for (int i = 0; i < firstValid; i++) atrArr[i] = v;
        }
        return AtrSeries.builder()
                .atr(atrArr)
                .barTimestamps(timestamps)
                .symbol(symbol)
                .timeframe(timeframe)
                .build();
    }
}
