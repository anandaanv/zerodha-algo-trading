package com.dtech.algo.strategy.builder;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.strategy.TradeStrategy;
import com.dtech.algo.strategy.config.StrategyConfig;

public interface StrategyBuilder {
  TradeStrategy buildStrategy(StrategyConfig strategyConfig) throws StrategyException;
}
