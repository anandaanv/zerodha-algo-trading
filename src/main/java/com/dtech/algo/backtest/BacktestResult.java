package com.dtech.algo.backtest;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class BacktestResult {

  Map<String, Double> aggregatesResults;
  List<TradeRecord> tradingRecord;

}
