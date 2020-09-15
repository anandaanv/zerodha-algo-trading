package com.dtech.kitecon.strategy;

import lombok.Builder;
import lombok.Data;
import org.ta4j.core.Strategy;

@Data
@Builder
public class TradingStrategy {

  private String name;
  private Strategy buyStrategy;
  private Strategy sellStrategy;
  private TradeDirection tradeDirection;

}
