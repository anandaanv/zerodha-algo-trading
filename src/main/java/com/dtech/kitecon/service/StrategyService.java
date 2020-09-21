package com.dtech.kitecon.service;

import com.dtech.kitecon.strategy.backtest.BackTestingHandler;
import com.dtech.kitecon.strategy.backtest.BacktestSummary;
import com.dtech.kitecon.strategy.builder.StrategyBuilder;
import com.dtech.kitecon.strategy.sets.StrategySet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StrategyService {

  private final BackTestingHandler backTestingHandler;
  private final StrategySet strategySet;

  public BacktestSummary testStrategy(String instrument, String strategyName) {
    return backTestingHandler.execute(instrument, strategySet.getStrategy(strategyName));
  }

}
