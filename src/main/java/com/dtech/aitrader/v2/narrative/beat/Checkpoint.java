package com.dtech.aitrader.v2.narrative.beat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Checkpoint {
  int bar;

  @JsonProperty("macd_line")
  double macdLine;

  @JsonProperty("signal_line")
  Double signalLine;

  Double histogram;
}
