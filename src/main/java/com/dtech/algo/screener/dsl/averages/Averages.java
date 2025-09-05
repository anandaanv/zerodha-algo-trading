package com.dtech.algo.screener.dsl.averages;

import com.dtech.algo.screener.ScreenerContext;
import com.dtech.algo.screener.dsl.KDsl;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

public final class Averages {
    private Averages() {}

    public static KDsl.SeriesExpr sma(ScreenerContext ctx, String alias, int period) {
        BarSeries series = ctx.getSeries(alias);
        if (series == null) return KDsl.SeriesExpr.nan(ctx);
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        SMAIndicator ind = new SMAIndicator(close, period);
        return KDsl.SeriesExpr.of(ctx, i -> ind.getValue(i).doubleValue());
    }

    public static KDsl.SeriesExpr ema(ScreenerContext ctx, String alias, int period) {
        BarSeries series = ctx.getSeries(alias);
        if (series == null) return KDsl.SeriesExpr.nan(ctx);
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        EMAIndicator ind = new EMAIndicator(close, period);
        return KDsl.SeriesExpr.of(ctx, i -> ind.getValue(i).doubleValue());
    }
}
