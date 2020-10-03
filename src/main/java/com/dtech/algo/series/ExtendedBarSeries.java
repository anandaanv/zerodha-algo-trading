package com.dtech.algo.series;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.Delegate;
import org.ta4j.core.BarSeries;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@ToString
public class ExtendedBarSeries implements IntervalBarSeries {

  @Delegate(types = BarSeries.class)
  private BarSeries delegate;

  private Interval interval;
  private SeriesType seriesType;
  private String instrument;
}
