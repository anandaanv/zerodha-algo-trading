package com.dtech.aitrader.v2.narrative.beat;

import com.fasterxml.jackson.annotation.JsonValue;

public enum IndicatorComponent {
  MACD_LINE("macd_line"),
  SIGNAL_LINE("signal_line"),
  HISTOGRAM("histogram"),
  MACD_ALL("macd_all"),
  RSI("rsi"),
  RSI_ALL("rsi_all"),
  STOCH_K("stoch_k"),
  STOCH_D("stoch_d"),
  STOCH_ALL("stoch_all"),
  STOCHRSI_K("stochrsi_k"),
  STOCHRSI_D("stochrsi_d"),
  STOCHRSI_ALL("stochrsi_all"),
  ADX("adx"),
  PLUS_DI("plus_di"),
  MINUS_DI("minus_di"),
  ADX_DMI("adx_dmi"),
  EMA20("ema20"),
  EMA50("ema50"),
  EMA100("ema100"),
  EMA200("ema200"),
  EMA_STACK("ema_stack");

  private final String jsonValue;

  IndicatorComponent(String jsonValue) {
    this.jsonValue = jsonValue;
  }

  @JsonValue
  public String getJsonValue() {
    return jsonValue;
  }
}
