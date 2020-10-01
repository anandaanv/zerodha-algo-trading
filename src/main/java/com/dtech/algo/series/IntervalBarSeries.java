package com.dtech.algo.series;

import org.ta4j.core.BarSeries;

public interface IntervalBarSeries extends BarSeries {
  Interval getInterval();
  SeriesType getSeriesType();
  String getInstrument();
}
