package com.dtech.algo.strategy.config;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@AllArgsConstructor
@Builder
@ToString
public class IndicatorConfig {
  private String key;
  private String indicatorName;
  private List<IndicatorInput> inputs;
}
