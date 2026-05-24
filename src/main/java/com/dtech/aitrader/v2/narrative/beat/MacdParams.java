package com.dtech.aitrader.v2.narrative.beat;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class MacdParams {
  int fast;
  int slow;
  int signal;
}
