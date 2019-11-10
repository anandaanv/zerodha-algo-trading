package com.dtech.kitecon.strategy.builder;

import com.dtech.kitecon.data.Instrument;
import org.ta4j.core.Strategy;
import org.ta4j.core.TimeSeries;

import java.util.Map;

public interface StrategyBuilder {
    Strategy build(Instrument tradingIdentity, Map<Instrument, TimeSeries> timeSeriesMap);
    String getName();
}
