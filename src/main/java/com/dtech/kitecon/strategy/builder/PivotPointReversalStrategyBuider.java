package com.dtech.kitecon.strategy.builder;

import com.dtech.kitecon.data.Instrument;
import org.springframework.stereotype.Component;
import org.ta4j.core.*;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
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
public class PivotPointReversalStrategyBuider extends BaseStrategyBuilder {

    @Override
    public Strategy build(Instrument tradingIdentity, Map<Instrument, TimeSeries> timeSeriesMap) {
        return create3DaySmaUnderStrategy(timeSeriesMap.get(tradingIdentity));
    }

    @Override
    public String getName() {
        return "PivotPointReversalBullish";
    }

    private static Strategy create3DaySmaUnderStrategy(TimeSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        OpenPriceIndicator openPriceIndicator1 = new OpenPriceIndicator(series);
        LowestValueIndicator lowestPrice = new LowestValueIndicator(new ClosePriceIndicator(series), 10);
        PivotPointIndicator pivotPoints = new PivotPointIndicator(series, TimeLevel.BARBASED);
        StandardReversalIndicator reversalIndicator = new StandardReversalIndicator(pivotPoints, PivotLevel.SUPPORT_1);
        SMAIndicator tenSMA = new SMAIndicator(closePrice, 10);
        SMAIndicator fiftySMA1 = new SMAIndicator(closePrice, 50);
        SMAIndicator twoHundredSMA = new SMAIndicator(closePrice, 200);
        MACDIndicator macdIndicator = new MACDIndicator(closePrice);

        VolumeIndicator volumeIndicator = new VolumeIndicator(series);
        SMAIndicator voluemSma10 = new SMAIndicator(volumeIndicator, 10);
        SMAIndicator voluemSma50 = new SMAIndicator(volumeIndicator, 50);


        Rule entryRule = new OverIndicatorRule(reversalIndicator, lowestPrice)
                .and(new OverIndicatorRule(macdIndicator, 0.01))
                .and(new OverIndicatorRule(openPriceIndicator1, reversalIndicator))
                .and(new OverIndicatorRule(openPriceIndicator1, tenSMA))
                .and(new OverIndicatorRule(voluemSma10, voluemSma50))
                .and(new UnderIndicatorRule(new RSIIndicator(closePrice, 14), 55))
                .and(new OverIndicatorRule(new RSIIndicator(closePrice, 14), 30))
                .and(new UnderIndicatorRule(closePrice, fiftySMA1));


        Rule exitRule = new StopGainRule(closePrice, 5)
                .or(new StopLossRule(closePrice, 2))
                .or(new TrailingStopLossRule(closePrice, PrecisionNum.valueOf(1)));

        return new BaseStrategy (
                entryRule, exitRule
        );
    }

}
