package com.dtech.kitecon.service;

import com.dtech.kitecon.strategy.backtest.BackTestingHandler;
import com.dtech.kitecon.strategy.backtest.BacktestSummary;
import com.dtech.kitecon.strategy.builder.StrategyBuilder;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StrategyService {

  private final Set<StrategyBuilder> strategyBuilders;
  private final BackTestingHandler backTestingHandler;
  private Map<String, StrategyBuilder> strategyBuilderMap;

  @PostConstruct
  public void buildStrategyBuilderMap() {
    strategyBuilderMap = strategyBuilders.stream()
        .collect(Collectors.toMap(s -> s.getName(), s -> s));
  }

  public BacktestSummary testStrategy(String instrument, String strategyName) {
    return backTestingHandler.execute(instrument, strategyBuilderMap.get(strategyName));
  }

}
