package com.dtech.kitecon.strategy.builder;

import com.dtech.kitecon.data.Instrument;
import org.springframework.stereotype.Component;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Rule;
import org.ta4j.core.Strategy;
import org.ta4j.core.TimeSeries;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.*;
import org.ta4j.core.indicators.pivotpoints.PivotLevel;
import org.ta4j.core.indicators.pivotpoints.PivotPointIndicator;
import org.ta4j.core.indicators.pivotpoints.StandardReversalIndicator;
import org.ta4j.core.indicators.pivotpoints.TimeLevel;
import org.ta4j.core.num.PrecisionNum;
import org.ta4j.core.trading.rules.*;

import java.util.Map;

@Component
public class PivotPointReversalSellStrategyBuider extends BaseStrategyBuilder {

    @Override
    public Strategy build(Instrument tradingIdentity, Map<Instrument, TimeSeries> timeSeriesMap) {
        return create3DaySmaUnderStrategy(timeSeriesMap.get(tradingIdentity));
    }

    @Override
    public String getName() {
        return "PivotPointReversalBearish";
    }

    private static Strategy create3DaySmaUnderStrategy(TimeSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        OpenPriceIndicator openPriceIndicator1 = new OpenPriceIndicator(series);
        MinPriceIndicator minPriceIndicator = new MinPriceIndicator(series);
        HighestValueIndicator highestPrice = new HighestValueIndicator(new ClosePriceIndicator(series), 10);
        PivotPointIndicator pivotPoints = new PivotPointIndicator(series, TimeLevel.BARBASED);
        StandardReversalIndicator reversalIndicator = new StandardReversalIndicator(pivotPoints, PivotLevel.RESISTANCE_1);
        MACDIndicator macdIndicator = new MACDIndicator(closePrice);
        SMAIndicator tenSMA = new SMAIndicator(closePrice, 10);
        SMAIndicator fiftySMA1 = new SMAIndicator(closePrice, 50);

        Rule entryRule = new UnderIndicatorRule(reversalIndicator, highestPrice)
                .and(new UnderIndicatorRule(closePrice, tenSMA))
                .and(new UnderIndicatorRule(macdIndicator, tenSMA))
                .and(new UnderIndicatorRule(openPriceIndicator1, reversalIndicator))
                .and(new OverIndicatorRule(closePrice, fiftySMA1));

        Rule exitRule = new StopGainRule(closePrice, -2.5)
                .or(new StopLossRule(closePrice, -0.7))
                .or(new TrailingStopLossRule(closePrice, PrecisionNum.valueOf(1)));

        return new BaseStrategy (entryRule, exitRule);
    }

}
