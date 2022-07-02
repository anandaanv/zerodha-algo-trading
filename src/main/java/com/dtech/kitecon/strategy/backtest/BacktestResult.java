package com.dtech.kitecon.strategy.backtest;

import java.util.List;
import java.util.Map;
import lombok.Value;
import org.ta4j.core.Position;

@Value
public class BacktestResult {

  private Map<String, Double> aggregatesResults;
  private List<Position> tradingRecord;

}
