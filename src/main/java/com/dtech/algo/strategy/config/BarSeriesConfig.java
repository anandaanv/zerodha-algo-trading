package com.dtech.algo.strategy.config;

import com.dtech.algo.series.Interval;
import com.dtech.algo.series.SeriesType;
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
public class BarSeriesConfig {
  private Interval interval;
  private SeriesType seriesType;
  private String instrument;
}
