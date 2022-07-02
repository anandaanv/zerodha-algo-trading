package com.dtech.kitecon.strategy.builder;

import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.strategy.TradeDirection;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Strategy;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.rules.OverIndicatorRule;
import org.ta4j.core.rules.UnderIndicatorRule;

@Component
public class SimpleMovingAverageStrategyBuider extends BaseStrategyBuilder {

  private static Strategy create3DaySmaUnderStrategy(BarSeries series) {
    ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
    SMAIndicator sma = new SMAIndicator(closePrice, 3);
    return new BaseStrategy(
        new UnderIndicatorRule(sma, closePrice),
        new OverIndicatorRule(sma, closePrice)
    );
  }

  @Override
  protected Strategy getSellStrategy(Instrument tradingIdentity,
      Map<Instrument, BarSeries> barSeriesMap,
      Map<String, String> sell) {
    return null;
  }

  @Override
  public Strategy getBuyStrategy(Instrument tradingIdentity,
      Map<Instrument, BarSeries> BarSeriesMap,
      Map<String, String> config) {
    return create3DaySmaUnderStrategy(BarSeriesMap.get(tradingIdentity));
  }

  @Override
  public TradeDirection getTradeDirection() {
    return TradeDirection.Buy;
  }

  @Override
  public String getName() {
    return "SimpleMovingAverage";
  }

}
