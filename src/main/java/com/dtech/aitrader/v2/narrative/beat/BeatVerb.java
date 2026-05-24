package com.dtech.aitrader.v2.narrative.beat;

import com.fasterxml.jackson.annotation.JsonValue;

public enum BeatVerb {
  PEAKED("peaked"),
  TROUGHED("troughed"),
  CROSSED("crossed"),
  THRUST("thrust"),
  FAILED_ATTEMPT("failed_attempt"),
  REGIME_CHANGE("regime_change"),
  DIVERGED_FROM_PRICE("diverged_from_price"),
  ENTERED_ZONE("entered_zone"),
  EXITED_ZONE("exited_zone"),
  CURRENTLY("currently");

  private final String jsonValue;

  BeatVerb(String jsonValue) {
    this.jsonValue = jsonValue;
  }

  @JsonValue
  public String getJsonValue() {
    return jsonValue;
  }
}
