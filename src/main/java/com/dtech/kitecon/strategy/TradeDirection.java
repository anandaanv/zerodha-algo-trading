package com.dtech.kitecon.strategy;

public enum TradeDirection {
  Buy,
  Sell,
  Both;

  public boolean isBuy() {
    return this == Buy || this == Both;
  }

  public boolean isSell() {
    return this == Sell || this == Both;
  }
}
