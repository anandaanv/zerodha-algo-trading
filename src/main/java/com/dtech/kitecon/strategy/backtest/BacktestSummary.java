package com.dtech.kitecon.strategy.backtest;

import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class BacktestSummary {

  Map<String, Map<?, ?>> summary;
  List<BacktestResult> results;

}
