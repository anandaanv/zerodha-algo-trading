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

  // MACD-specific fields (set by MacdIndicatorConfig). null for non-MACD indicators.
  @JsonProperty("macd_line")
  Double macdLine;

  @JsonProperty("signal_line")
  Double signalLine;

  Double histogram;

  // RSI-specific field (set by RsiIndicatorConfig). null for non-RSI indicators.
  // Added per owner validation memo d3020077 Fix 2 — prior to this fix, RSI checkpoints
  // reused macd_line for the RSI value, which is false labeling (a Checkpoint reader
  // keying off field name got an RSI value labeled as MACD line).
  Double rsi;
}
