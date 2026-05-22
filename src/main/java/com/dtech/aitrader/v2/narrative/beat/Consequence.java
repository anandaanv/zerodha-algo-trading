package com.dtech.aitrader.v2.narrative.beat;

import com.fasterxml.jackson.annotation.JsonValue;

public enum Consequence {
  CONFIRMED("confirmed"),
  FAILED("failed"),
  ONGOING("ongoing");

  private final String jsonValue;

  Consequence(String jsonValue) {
    this.jsonValue = jsonValue;
  }

  @JsonValue
  public String getJsonValue() {
    return jsonValue;
  }
}
