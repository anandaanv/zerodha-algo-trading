package com.dtech.aitrader.v2.narrative.beat;

import com.fasterxml.jackson.annotation.JsonValue;

public enum Tier {
  HISTORY("history"),
  RECENT("recent"),
  PRESENT("present");

  private final String jsonValue;

  Tier(String jsonValue) {
    this.jsonValue = jsonValue;
  }

  @JsonValue
  public String getJsonValue() {
    return jsonValue;
  }
}
