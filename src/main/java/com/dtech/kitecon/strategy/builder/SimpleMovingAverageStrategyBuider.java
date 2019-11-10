package com.dtech.kitecon.strategy.builder;

import com.dtech.kitecon.data.Instrument;
import org.springframework.stereotype.Component;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Strategy;
import org.ta4j.core.TimeSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.trading.rules.OverIndicatorRule;
import org.ta4j.core.trading.rules.UnderIndicatorRule;

import java.util.Map;

@Component
public class SimpleMovingAverageStrategyBuider extends BaseStrategyBuilder {

    @Override
    public Strategy build(Instrument tradingIdentity, Map<Instrument, TimeSeries> timeSeriesMap) {
        return create3DaySmaUnderStrategy(timeSeriesMap.get(tradingIdentity));
    }

    @Override
    public String getName() {
        return "SimpleMovingAverage";
    }

    private static Strategy create3DaySmaUnderStrategy(TimeSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator sma = new SMAIndicator(closePrice, 3);
        return new BaseStrategy(
                new UnderIndicatorRule(sma, closePrice),
                new OverIndicatorRule(sma, closePrice)
        );
    }

}
