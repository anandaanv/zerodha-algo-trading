package com.dtech.algo.strategy;

import com.dtech.kitecon.strategy.TradeDirection;
import org.ta4j.core.Strategy;

public interface TradeStrategy extends Strategy {
  public TradeDirection getDirection();
}
