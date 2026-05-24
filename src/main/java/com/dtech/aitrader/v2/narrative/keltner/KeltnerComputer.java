package com.dtech.aitrader.v2.narrative.keltner;

import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.time.Instant;
import java.util.List;

/**
 * Computes Keltner Channels via ta4j. Middle = EMA(period) of close; outer = middle ± atrMult ×
 * ATR(atrPeriod). ADX precomputed alongside for band-walk disambiguation (same pattern as Bollinger).
 * EMA smoothing (per owner guidance: WILDER only for ATR + ADX, standard EMA elsewhere).
 */
public final class KeltnerComputer {

    private KeltnerComputer() {}

    public static KeltnerSeries compute(List<OhlcBarDTO> bars, int period, int atrPeriod,
                                         double atrMult, int adxPeriod,
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
        EMAIndicator ema = new EMAIndicator(close, period);
        ATRIndicator atr = new ATRIndicator(series, atrPeriod);
        ADXIndicator adxI = new ADXIndicator(series, adxPeriod);

        int n = bars.size();
        double[] mid = new double[n];
        double[] up = new double[n];
        double[] lo = new double[n];
        double[] w = new double[n];
        double[] cl = new double[n];
        double[] ax = new double[n];
        Instant[] timestamps = new Instant[n];
        for (int i = 0; i < n; i++) {
            double m = ema.getValue(i).doubleValue();
            double a = atr.getValue(i).doubleValue();
            mid[i] = m;
            up[i] = m + atrMult * a;
            lo[i] = m - atrMult * a;
            cl[i] = bars.get(i).getClose();
            ax[i] = adxI.getValue(i).doubleValue();
            timestamps[i] = Instant.ofEpochSecond(bars.get(i).getTime());
            double range = up[i] - lo[i];
            w[i] = m != 0 ? (range / m) * 100.0 : 0.0;
        }
        // Warmup clamp: replace early NaN/zero with first valid.
        int firstValid = -1;
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(mid[i]) && !Double.isNaN(up[i]) && !Double.isNaN(lo[i])
                    && !Double.isNaN(w[i]) && !Double.isNaN(ax[i]) && mid[i] != 0) {
                firstValid = i;
                break;
            }
        }
        if (firstValid > 0) {
            for (int i = 0; i < firstValid; i++) {
                mid[i] = mid[firstValid];
                up[i] = up[firstValid];
                lo[i] = lo[firstValid];
                w[i] = w[firstValid];
                ax[i] = ax[firstValid];
            }
        }
        return KeltnerSeries.builder()
                .middle(mid).upper(up).lower(lo).width(w).closes(cl).adx(ax)
                .barTimestamps(timestamps).symbol(symbol).timeframe(timeframe)
                .build();
    }
}
