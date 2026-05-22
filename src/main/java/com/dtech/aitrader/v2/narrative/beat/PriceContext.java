package com.dtech.aitrader.v2.narrative.beat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PriceContext {
  @JsonProperty("swing_state")
  SwingState swingState;

  @JsonProperty("vs_event")
  String vsEvent;

  @JsonProperty("price_value")
  Double priceValue;

  @JsonProperty("nearest_price_pivot")
  PricePivotRef nearestPricePivot;
}
