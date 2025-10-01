package com.dtech.algo.screener.dsl.bands;

import com.dtech.algo.screener.ScreenerContext;
import com.dtech.algo.screener.dsl.KDsl;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.num.DecimalNum;

public final class Bands {
    private Bands() {}

    public static BandsTriple bbands(ScreenerContext ctx, String alias, int period, double mult) {
        BarSeries series = ctx.getSeries(alias);
        if (series == null) {
            KDsl.SeriesExpr nan = KDsl.SeriesExpr.nan(ctx);
            return new BandsTriple(nan, nan, nan);
        }
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        SMAIndicator sma = new SMAIndicator(close, period);
        StandardDeviationIndicator std = new StandardDeviationIndicator(close, period);
        BollingerBandsMiddleIndicator mid = new BollingerBandsMiddleIndicator(sma);
        BollingerBandsUpperIndicator up = new BollingerBandsUpperIndicator(mid, std, DecimalNum.valueOf(mult));
        BollingerBandsLowerIndicator lo = new BollingerBandsLowerIndicator(mid, std, DecimalNum.valueOf(mult));

        KDsl.SeriesExpr midE = KDsl.SeriesExpr.of(ctx, alias, i -> mid.getValue(i).doubleValue());
        KDsl.SeriesExpr upE = KDsl.SeriesExpr.of(ctx, alias, i -> up.getValue(i).doubleValue());
        KDsl.SeriesExpr loE = KDsl.SeriesExpr.of(ctx, alias, i -> lo.getValue(i).doubleValue());
        return new BandsTriple(midE, upE, loE);
    }

    public static final class BandsTriple {
        public final KDsl.SeriesExpr mid;
        public final KDsl.SeriesExpr upper;
        public final KDsl.SeriesExpr lower;

        public BandsTriple(KDsl.SeriesExpr mid, KDsl.SeriesExpr upper, KDsl.SeriesExpr lower) {
            this.mid = mid;
            this.upper = upper;
            this.lower = lower;
        }
    }
}
