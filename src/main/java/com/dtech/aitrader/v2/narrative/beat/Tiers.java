package com.dtech.aitrader.v2.narrative.beat;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Tiers {
  List<Beat> history;
  List<Beat> recent;
  List<Beat> present;
}
