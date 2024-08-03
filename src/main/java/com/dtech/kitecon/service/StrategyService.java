package com.dtech.kitecon.service;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.strategy.backtest.BackTestingHandler;
import com.dtech.kitecon.strategy.backtest.BacktestSummary;
import com.dtech.kitecon.strategy.sets.StrategySet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StrategyService {

  private final BackTestingHandler backTestingHandler;
  private final StrategySet strategySet;

  public BacktestSummary testStrategy(String instrument, String strategyName, Interval interval) {
    return backTestingHandler.execute(instrument, strategySet.getStrategy(strategyName), interval);
  }

}
