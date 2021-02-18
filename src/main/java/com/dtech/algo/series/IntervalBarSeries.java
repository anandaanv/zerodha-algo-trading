package com.dtech.algo.series;

import org.ta4j.core.BarSeries;

import java.time.ZonedDateTime;

public interface IntervalBarSeries extends BarSeries {
  Interval getInterval();
  SeriesType getSeriesType();
  String getInstrument();
  void addBarWithTimeValidation(ZonedDateTime endTime, Number openPrice, Number highPrice, Number lowPrice,
                                Number closePrice, Number volume);
  }
