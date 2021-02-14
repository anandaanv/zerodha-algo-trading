package com.dtech.algo.strategy.builder.ifc;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.strategy.config.BarSeriesConfig;

public interface BarSeriesLoader {
  IntervalBarSeries loadBarSeries(BarSeriesConfig barSeriesConfig) throws StrategyException;
}
