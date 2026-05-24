package com.dtech.aitrader.v2.narrative.beat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Narrative {
  String indicator;
  MacdParams params;
  String symbol;
  String timeframe;

  @JsonProperty("bar0_date")
  String bar0Date;

  @JsonProperty("last_bar")
  LastBar lastBar;

  @JsonProperty("calc_note")
  String calcNote;

  Tiers tiers;

  @JsonProperty("verification_slices")
  VerificationSlices verificationSlices;
}
