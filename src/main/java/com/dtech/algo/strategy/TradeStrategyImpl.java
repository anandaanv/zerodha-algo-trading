package com.dtech.algo.strategy;

import com.dtech.kitecon.strategy.TradeDirection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Delegate;
import org.ta4j.core.Strategy;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradeStrategyImpl implements TradeStrategy {

  @Delegate
  private Strategy delegate;

  private TradeDirection direction;

}
