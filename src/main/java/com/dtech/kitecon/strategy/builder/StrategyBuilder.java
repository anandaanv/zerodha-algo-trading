package com.dtech.kitecon.strategy.builder;

import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.strategy.TradeDirection;
import com.dtech.kitecon.strategy.TradingStrategy;
import java.util.Map;
import org.ta4j.core.BarSeries;

public interface StrategyBuilder {

  TradeDirection getTradeDirection();

  String getName();

  TradingStrategy build(Instrument tradingIdentity,
      Map<Instrument, BarSeries> barSeriesMap, StrategyConfig config);
}
