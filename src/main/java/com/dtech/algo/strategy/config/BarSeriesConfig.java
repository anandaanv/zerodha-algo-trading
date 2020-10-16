package com.dtech.algo.strategy.config;

import com.dtech.algo.series.Exchange;
import com.dtech.algo.series.InstrumentType;
import com.dtech.algo.series.Interval;
import com.dtech.algo.series.SeriesType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDate;

@Data
@AllArgsConstructor
@Builder
@ToString
@NoArgsConstructor
public class BarSeriesConfig {
  private Interval interval;
  private SeriesType seriesType;
  private InstrumentType instrumentType;
  private Exchange exchange;
  private String instrument;
  private String name;
  private LocalDate startDate;
  private LocalDate endDate;
}
