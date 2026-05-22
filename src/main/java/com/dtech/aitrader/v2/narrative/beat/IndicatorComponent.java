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
  STOCH_ALL("stoch_all");

  private final String jsonValue;

  IndicatorComponent(String jsonValue) {
    this.jsonValue = jsonValue;
  }

  @JsonValue
  public String getJsonValue() {
    return jsonValue;
  }
}
