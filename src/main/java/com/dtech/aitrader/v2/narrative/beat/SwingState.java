package com.dtech.aitrader.v2.narrative.beat;

import com.fasterxml.jackson.annotation.JsonValue;

public enum SwingState {
  HH("HH"),
  HL("HL"),
  LH("LH"),
  LL("LL"),
  NONE("none");

  private final String jsonValue;

  SwingState(String jsonValue) {
    this.jsonValue = jsonValue;
  }

  @JsonValue
  public String getJsonValue() {
    return jsonValue;
  }
}
