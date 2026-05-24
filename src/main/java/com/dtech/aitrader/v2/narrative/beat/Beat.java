package com.dtech.aitrader.v2.narrative.beat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Beat {
  // Always present:
  @JsonProperty("what")
  BeatVerb what;

  @JsonProperty("component")
  IndicatorComponent component;

  @JsonProperty("when_bar")
  int whenBar;

  @JsonProperty("when_date")
  String whenDate;

  @JsonProperty("value")
  Double value;

  @JsonProperty("significance")
  Double significance;

  @JsonProperty("persisted_bars")
  Integer persistedBars;

  @JsonProperty("consequence")
  Consequence consequence;

  @JsonProperty("price_context")
  PriceContext priceContext;

  @JsonProperty("tier")
  Tier tier;

  @JsonProperty("ref")
  String ref;

  @JsonProperty("note")
  String note;

  // For currently:
  @JsonProperty("macd_line")
  Double macdLine;

  @JsonProperty("signal_line")
  Double signalLine;

  @JsonProperty("histogram")
  Double histogram;

  // For crossed:
  @JsonProperty("from")
  String from;

  @JsonProperty("to")
  String to;

  // For diverged_from_price:
  @JsonProperty("type")
  String type;

  @JsonProperty("direction")
  String direction;

  @JsonProperty("pivot_pair")
  List<PivotPairEntry> pivotPair;

  @JsonProperty("deeper_anchor")
  PivotPairEntry deeperAnchor;
}
