package com.dtech.algo.backtest;

import com.dtech.algo.strategy.config.BarSeriesConfig;
import com.dtech.algo.strategy.config.StrategyConfig;
import lombok.*;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BacktestInput {

    private List<BarSeriesConfig> barSeriesConfigs;
    private String barSeriesName;
    private StrategyConfig strategyConfig;

}
