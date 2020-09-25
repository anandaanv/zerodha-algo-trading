package com.dtech.kitecon.strategy.builder;

import com.dtech.kitecon.data.StrategyParameters;
import com.dtech.kitecon.strategy.TradeDirection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Builder
@AllArgsConstructor
public class StrategyConfig {
  private final List<StrategyParameters> params;

  public Map<String, String> getConfig(String key) {
    return params.stream().filter(strategyParameters -> strategyParameters.getStrategyType().equals(key))
        .collect(Collectors.toMap(StrategyParameters::getConfigKey, StrategyParameters::getValue));
  }

}
