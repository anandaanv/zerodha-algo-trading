package com.dtech.algo.strategy.config;

import com.dtech.kitecon.strategy.TradeDirection;
import java.util.List;
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

  private List<IndicatorConfig> indicators;

  private List<RuleConfig> rules;

}
