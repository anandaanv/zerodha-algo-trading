package com.dtech.algo.screener.dsl.oscillators;

import com.dtech.algo.screener.ScreenerContext;
import com.dtech.algo.screener.dsl.KDsl;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.StochasticOscillatorKIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

public final class Oscillators {
    private Oscillators() {}

    public static KDsl.SeriesExpr rsi(ScreenerContext ctx, String alias, int period) {
        BarSeries series = ctx.getSeries(alias);
        if (series == null) return KDsl.SeriesExpr.nan(ctx);
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        RSIIndicator ind = new RSIIndicator(close, period);
        return KDsl.SeriesExpr.of(ctx, alias, i -> ind.getValue(i).doubleValue());
    }

    /**
     * Stochastic %K smoothed by smoothK (simple MA).
     */
    public static KDsl.SeriesExpr stochK(ScreenerContext ctx, String alias, int kPeriod, int smoothK) {
        BarSeries series = ctx.getSeries(alias);
        if (series == null) return KDsl.SeriesExpr.nan(ctx);
        StochasticOscillatorKIndicator k = new StochasticOscillatorKIndicator(series, kPeriod);
        if (smoothK <= 1) {
            return KDsl.SeriesExpr.of(ctx, alias, i -> k.getValue(i).doubleValue());
        }
        SMAIndicator smoothed = new SMAIndicator(k, smoothK);
        return KDsl.SeriesExpr.of(ctx, alias, i -> smoothed.getValue(i).doubleValue());
    }
}
