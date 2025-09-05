package com.dtech.algo.screener.dsl.trend;

import com.dtech.algo.screener.ScreenerContext;
import com.dtech.algo.screener.dsl.KDsl;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.adx.MinusDIIndicator;
import org.ta4j.core.indicators.adx.PlusDIIndicator;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

public final class Trend {
    private Trend() {}

    public static Macd macd(ScreenerContext ctx, String alias, int fast, int slow, int signal) {
        BarSeries series = ctx.getSeries(alias);
        if (series == null) {
            KDsl.SeriesExpr nan = KDsl.SeriesExpr.nan(ctx);
            return new Macd(nan, nan, nan);
        }
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        MACDIndicator macd = new MACDIndicator(close, fast, slow);
        EMAIndicator sig = new EMAIndicator(macd, signal);
        KDsl.SeriesExpr macdExpr = KDsl.SeriesExpr.of(ctx, i -> macd.getValue(i).doubleValue());
        KDsl.SeriesExpr sigExpr = KDsl.SeriesExpr.of(ctx, i -> sig.getValue(i).doubleValue());
        KDsl.SeriesExpr histExpr = KDsl.SeriesExpr.of(ctx, i -> macd.getValue(i).minus(sig.getValue(i)).doubleValue());
        return new Macd(macdExpr, sigExpr, histExpr);
    }

    public static KDsl.SeriesExpr adx(ScreenerContext ctx, String alias, int period) {
        BarSeries series = ctx.getSeries(alias);
        if (series == null) return KDsl.SeriesExpr.nan(ctx);
        ADXIndicator adx = new ADXIndicator(series, period);
        return KDsl.SeriesExpr.of(ctx, i -> adx.getValue(i).doubleValue());
    }

    public static KDsl.SeriesExpr diPlus(ScreenerContext ctx, String alias, int period) {
        BarSeries series = ctx.getSeries(alias);
        if (series == null) return KDsl.SeriesExpr.nan(ctx);
        PlusDIIndicator di = new PlusDIIndicator(series, period);
        return KDsl.SeriesExpr.of(ctx, i -> di.getValue(i).doubleValue());
    }

    public static KDsl.SeriesExpr diMinus(ScreenerContext ctx, String alias, int period) {
        BarSeries series = ctx.getSeries(alias);
        if (series == null) return KDsl.SeriesExpr.nan(ctx);
        MinusDIIndicator di = new MinusDIIndicator(series, period);
        return KDsl.SeriesExpr.of(ctx, i -> di.getValue(i).doubleValue());
    }

    public static final class Macd {
        public final KDsl.SeriesExpr macd;
        public final KDsl.SeriesExpr signal;
        public final KDsl.SeriesExpr hist;
        public Macd(KDsl.SeriesExpr macd, KDsl.SeriesExpr signal, KDsl.SeriesExpr hist) {
            this.macd = macd;
            this.signal = signal;
            this.hist = hist;
        }
    }
}
