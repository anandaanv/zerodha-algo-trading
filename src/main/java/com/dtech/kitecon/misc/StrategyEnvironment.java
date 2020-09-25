package com.dtech.kitecon.misc;

public enum StrategyEnvironment {
  DEV,
  PROD;

  public boolean isProd() {
    return this == PROD;
  }

  public boolean isDev() {
    return this == DEV;
  }
}
