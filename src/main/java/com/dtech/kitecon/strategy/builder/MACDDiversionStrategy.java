package com.dtech.kitecon.strategy.builder;

import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.strategy.TradeDirection;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Rule;
import org.ta4j.core.Strategy;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.rules.OverIndicatorRule;
import org.ta4j.core.rules.StopGainRule;
import org.ta4j.core.rules.StopLossRule;
import org.ta4j.core.rules.TrailingStopLossRule;
import org.ta4j.core.rules.UnderIndicatorRule;

@Component
public class MACDDiversionStrategy extends BaseStrategyBuilder {

  private static Strategy create3DaySmaUnderStrategy(BarSeries series) {
    ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

    MACDIndicator macdIndicator = new MACDIndicator(closePrice);
    Rule entryRule = new OverIndicatorRule(macdIndicator, 1)
        .and(new UnderIndicatorRule(new RSIIndicator(closePrice, 14), 55))
        .and(new OverIndicatorRule(new RSIIndicator(closePrice, 14), 30));

    Rule exitRule = new StopGainRule(closePrice, 5)
        .or(new StopLossRule(closePrice, 2))
        .or(new TrailingStopLossRule(closePrice, DecimalNum.valueOf(1)));

    return new BaseStrategy(entryRule, exitRule);
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
    return "MACDDivergence";
  }

}
