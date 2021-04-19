package com.dtech.algo.strategy.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunnerConfig {
    private List<BarSeriesConfig> barSeriesConfigs;
    private String barSeriesName;
    private StrategyConfig strategyConfig;
}
