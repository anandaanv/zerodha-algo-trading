package com.dtech.algo.backtest;

import com.dtech.algo.strategy.config.BarSeriesConfig;
import com.dtech.algo.strategy.config.StrategyConfig;
import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class BacktestInput {

    List<BarSeriesConfig> barSeriesConfigs;
    String barSeriesName;
    StrategyConfig strategyConfig;

}
