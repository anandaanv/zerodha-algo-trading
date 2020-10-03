package com.dtech.algo.exception;

public class StrategyException extends Exception {

  public StrategyException() {
    super();
  }

  public StrategyException(String message) {
    super(message);
  }

  public StrategyException(String message, Throwable cause) {
    super(message, cause);
  }

  public StrategyException(Throwable cause) {
    super(cause);
  }

  protected StrategyException(String message, Throwable cause, boolean enableSuppression,
      boolean writableStackTrace) {
    super(message, cause, enableSuppression, writableStackTrace);
  }
}
