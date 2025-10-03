package com.dtech.algo.series;

import org.ta4j.core.BarSeries;
import org.ta4j.core.num.DecimalNum;

import java.time.Instant;
import java.time.ZonedDateTime;

public interface IntervalBarSeries extends BarSeries {
  Interval getInterval();
  SeriesType getSeriesType();
  String getInstrument();
  void addBarWithTimeValidation(Instant endTime, Number openPrice, Number highPrice, Number lowPrice,
                                Number closePrice, Number volume);

  // Convenience overload for business logic using ZonedDateTime
  default void addBarWithTimeValidation(ZonedDateTime endTime, Number openPrice, Number highPrice, Number lowPrice,
                                        Number closePrice, Number volume) {
    addBarWithTimeValidation(endTime.toInstant(), openPrice, highPrice, lowPrice, closePrice, volume);
  }

  default DecimalNum numOf(Number num) {
        return DecimalNum.valueOf(num.doubleValue());
  }

}



