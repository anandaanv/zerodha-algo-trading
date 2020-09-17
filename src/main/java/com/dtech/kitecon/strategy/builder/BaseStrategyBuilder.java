package com.dtech.kitecon.strategy.builder;

import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.strategy.TradingStrategy;
import java.util.Map;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Strategy;

public abstract class BaseStrategyBuilder implements StrategyBuilder {

  @Override
  public TradingStrategy build(Instrument tradingIdentity,
      Map<Instrument, BarSeries> barSeriesMap) {
    return TradingStrategy.builder()
        .buyStrategy(getBuyStrategy(tradingIdentity, barSeriesMap))
        .sellStrategy(getSellStrategy(tradingIdentity, barSeriesMap))
        .tradeDirection(getTradeDirection())
        .name(getName())
        .build();
  }

  protected abstract Strategy getSellStrategy(Instrument tradingIdentity,
      Map<Instrument, BarSeries> barSeriesMap);

  protected abstract Strategy getBuyStrategy(Instrument tradingIdentity,
      Map<Instrument, BarSeries> barSeriesMap);
}
