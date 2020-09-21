package com.dtech.kitecon.service;

import com.dtech.kitecon.strategy.builder.StrategyBuilder;
import com.dtech.kitecon.strategy.exec.ProductionHandler;
import com.dtech.kitecon.strategy.sets.StrategySet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ExecutionService {
  private final StrategySet strategySet;
  private final ProductionHandler productionHandler;

  public String startStrategy(String strategyName, String instrumentName, String direction) {
    StrategyBuilder strategy = strategySet.getStrategy(strategyName);
    return productionHandler.startStrategy(instrumentName, strategy, direction);
  }

}
