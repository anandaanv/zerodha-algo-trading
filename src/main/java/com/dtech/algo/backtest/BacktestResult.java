package com.dtech.algo.backtest;

import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
public class BacktestResult {

  Map<String, Double> aggregatesResults;
  List<TradeRecord> tradingRecord;

}
