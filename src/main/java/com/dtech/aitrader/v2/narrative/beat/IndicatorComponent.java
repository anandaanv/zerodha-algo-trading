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
  EMA_STACK("ema_stack"),
  BB_MIDDLE("bb_middle"),
  BB_UPPER("bb_upper"),
  BB_LOWER("bb_lower"),
  BB_WIDTH("bb_width"),
  BB_PERCENT("bb_percent"),
  BOLLINGER("bollinger"),
  ROC("roc"),
  OBV("obv"),
  AROON_UP("aroon_up"),
  AROON_DOWN("aroon_down"),
  AROON_OSC("aroon_osc"),
  AROON("aroon"),
  KELTNER_UPPER("keltner_upper"),
  KELTNER_MIDDLE("keltner_middle"),
  KELTNER_LOWER("keltner_lower"),
  KELTNER_WIDTH("keltner_width"),
  KELTNER("keltner"),
  DONCHIAN_UPPER("donchian_upper"),
  DONCHIAN_LOWER("donchian_lower"),
  DONCHIAN_MIDDLE("donchian_middle"),
  DONCHIAN_WIDTH("donchian_width"),
  DONCHIAN("donchian"),
  ICHIMOKU_TENKAN("ichimoku_tenkan"),
  ICHIMOKU_KIJUN("ichimoku_kijun"),
  ICHIMOKU_SENKOU_A("ichimoku_senkou_a"),
  ICHIMOKU_SENKOU_B("ichimoku_senkou_b"),
  ICHIMOKU_CHIKOU("ichimoku_chikou"),
  ICHIMOKU("ichimoku"),
  VWAP("vwap"),
  ATR("atr");

  private final String jsonValue;

  IndicatorComponent(String jsonValue) {
    this.jsonValue = jsonValue;
  }

  @JsonValue
  public String getJsonValue() {
    return jsonValue;
  }
}
