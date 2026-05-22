package com.dtech.aitrader.v2.narrative.bollinger;

import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

import java.time.Instant;
import java.util.List;

/**
 * Bollinger Bands(20, 2) via ta4j + ADX(14) for band-tag regime disambiguation. The ADX series
 * is precomputed here so {@link BollingerIndicatorConfig} can annotate each band-tag beat with
 * the current trend-strength context per delta 0c1c601e Section 5 — the engine emits the event,
 * the LLM judges walk-vs-reversion.
 */
public final class BollingerComputer {

    private BollingerComputer() {}

    public static BollingerSeries compute(List<OhlcBarDTO> bars, int period, double stdevMult,
                                           int adxPeriod, String symbol, String timeframe) {
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
        SMAIndicator sma = new SMAIndicator(close, period);
        BollingerBandsMiddleIndicator midI = new BollingerBandsMiddleIndicator(sma);
        StandardDeviationIndicator stdev = new StandardDeviationIndicator(close, period);
        org.ta4j.core.num.Num k = series.numFactory().numOf(stdevMult);
        BollingerBandsUpperIndicator upperI = new BollingerBandsUpperIndicator(midI, stdev, k);
        BollingerBandsLowerIndicator lowerI = new BollingerBandsLowerIndicator(midI, stdev, k);
        ADXIndicator adxI = new ADXIndicator(series, adxPeriod);

        int n = bars.size();
        double[] mid = new double[n];
        double[] up = new double[n];
        double[] lo = new double[n];
        double[] w = new double[n];
        double[] pb = new double[n];
        double[] cl = new double[n];
        double[] ax = new double[n];
        Instant[] timestamps = new Instant[n];

        for (int i = 0; i < n; i++) {
            mid[i] = midI.getValue(i).doubleValue();
            up[i] = upperI.getValue(i).doubleValue();
            lo[i] = lowerI.getValue(i).doubleValue();
            cl[i] = bars.get(i).getClose();
            ax[i] = adxI.getValue(i).doubleValue();
            timestamps[i] = Instant.ofEpochSecond(bars.get(i).getTime());
            // BBW % and %B
            double range = up[i] - lo[i];
            w[i] = mid[i] != 0 ? (range / mid[i]) * 100.0 : 0.0;
            pb[i] = range > 0 ? (cl[i] - lo[i]) / range : 0.5;
        }
        // Warmup: same dynamic-clamp pattern.
        int firstValid = -1;
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(mid[i]) && !Double.isNaN(up[i]) && !Double.isNaN(lo[i])
                    && !Double.isNaN(w[i]) && !Double.isNaN(ax[i])) {
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
                pb[i] = pb[firstValid];
                ax[i] = ax[firstValid];
            }
        }
        return BollingerSeries.builder()
                .middle(mid).upper(up).lower(lo).width(w).percentB(pb).closes(cl).adx(ax)
                .barTimestamps(timestamps).symbol(symbol).timeframe(timeframe)
                .build();
    }
}
