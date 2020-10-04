package com.dtech.algo.rules;

import com.dtech.algo.indicators.IndicatorConstructor;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;

@Data
@AllArgsConstructor
@Builder
@ToString
public class RuleInfo {
  private String name;
  private List<RuleConstructor> constructors;
}
