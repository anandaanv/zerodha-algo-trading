package com.dtech.kitecon.strategy.builder;

import com.dtech.kitecon.data.Instrument;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Strategy;
import org.ta4j.core.BarSeries;

import java.util.Map;

public interface StrategyBuilder {
    Strategy build(Instrument tradingIdentity, Map<Instrument, BarSeries> BarSeriesMap);
    String getName();
}
