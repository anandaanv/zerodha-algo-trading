package com.dtech.algo.strategy.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@AllArgsConstructor
@Builder
@NoArgsConstructor
@ToString
public enum IndicatorInputType {
  BarSeries,
  Indicator,
  Number;
}
