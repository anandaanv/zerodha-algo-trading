package com.dtech.algo.indicators;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;

@Data
@AllArgsConstructor
@Builder
@ToString
public class IndicatorInfo {
  private String name;
  private List<IndicatorConstructor> constructors;
}
