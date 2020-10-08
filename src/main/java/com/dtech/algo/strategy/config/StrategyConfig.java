package com.dtech.algo.strategy.config;

import com.dtech.kitecon.strategy.TradeDirection;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@AllArgsConstructor
@Builder
@ToString
public class StrategyConfig {
  // Strategy name
  private String strategyName;

  // Trade direction - buy/ sell
  private TradeDirection direction;

  private Map<String, String> constants;

  private List<IndicatorConfig> indicators;

  private List<RuleConfig> rules;

  private List<String> entry;
  private List<String> exit;

}
