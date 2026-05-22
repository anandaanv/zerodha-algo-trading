package com.dtech.aitrader.v2.narrative.beat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VerificationSlices {
  String comment;

  @JsonProperty("checkpoints")
  List<Checkpoint> checkpoints;
}
