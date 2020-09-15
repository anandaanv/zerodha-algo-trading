package com.dtech.kitecon.strategy.builder;

import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.strategy.TradeDirection;
import com.dtech.kitecon.strategy.TradingStrategy;
import org.ta4j.core.BarSeries;

import java.util.Map;

public interface StrategyBuilder {
    TradeDirection getTradeDirection();
    TradingStrategy build(Instrument tradingIdentity, Map<Instrument, BarSeries> BarSeriesMap);
    String getName();
}
