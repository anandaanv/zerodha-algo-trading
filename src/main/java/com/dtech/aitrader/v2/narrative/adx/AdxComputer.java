package com.dtech.aitrader.v2.narrative.adx;

import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.adx.MinusDIIndicator;
import org.ta4j.core.indicators.adx.PlusDIIndicator;

import java.time.Instant;
import java.util.List;

/**
 * Computes ADX(14) + +DI(14) + −DI(14) via ta4j. Wilder smoothing is what ta4j's ADXIndicator
 * uses internally — matches the delta spec.
 *
 * <p>Same warmup-clamp pattern as RSI/Stoch: dynamic first-non-NaN detection, then clamp the
 * pre-stable bars to the first stable value so the adaptive-significance pivot ATR isn't poisoned.
 */
public final class AdxComputer {

    private AdxComputer() {}

    public static AdxSeries compute(List<OhlcBarDTO> bars, int period, String symbol, String timeframe) {
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
        ADXIndicator adxIndicator = new ADXIndicator(series, period);
        PlusDIIndicator plusDi = new PlusDIIndicator(series, period);
        MinusDIIndicator minusDi = new MinusDIIndicator(series, period);

        int n = bars.size();
        double[] adxArr = new double[n];
        double[] pdiArr = new double[n];
        double[] mdiArr = new double[n];
        Instant[] timestamps = new Instant[n];
        for (int i = 0; i < n; i++) {
            adxArr[i] = adxIndicator.getValue(i).doubleValue();
            pdiArr[i] = plusDi.getValue(i).doubleValue();
            mdiArr[i] = minusDi.getValue(i).doubleValue();
            timestamps[i] = Instant.ofEpochSecond(bars.get(i).getTime());
        }

        int firstValid = -1;
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(adxArr[i]) && !Double.isNaN(pdiArr[i]) && !Double.isNaN(mdiArr[i])) {
                firstValid = i;
                break;
            }
        }
        if (firstValid > 0) {
            double a = adxArr[firstValid], p = pdiArr[firstValid], m = mdiArr[firstValid];
            for (int i = 0; i < firstValid; i++) {
                adxArr[i] = a;
                pdiArr[i] = p;
                mdiArr[i] = m;
            }
        }
        return AdxSeries.builder()
                .adx(adxArr)
                .plusDi(pdiArr)
                .minusDi(mdiArr)
                .barTimestamps(timestamps)
                .symbol(symbol)
                .timeframe(timeframe)
                .build();
    }
}
