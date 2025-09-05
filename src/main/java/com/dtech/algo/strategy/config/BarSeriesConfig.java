package com.dtech.algo.strategy.config;

import com.dtech.algo.series.Exchange;
import com.dtech.algo.series.InstrumentType;
import com.dtech.algo.series.Interval;
import com.dtech.algo.series.SeriesType;
import lombok.*;
import org.mapstruct.control.DeepClone;

import java.time.LocalDate;

@Data
@AllArgsConstructor
@Builder
@ToString
@NoArgsConstructor
public class BarSeriesConfig implements Cloneable {
  @EqualsAndHashCode.Include
  private Interval interval;
  private SeriesType seriesType;
  private InstrumentType instrumentType;
  private Exchange exchange;
  @EqualsAndHashCode.Include
  private String instrument;
  private String name;
  private LocalDate startDate;
  private LocalDate endDate;

  public BarSeriesConfig clone() throws CloneNotSupportedException {
    return (BarSeriesConfig) super.clone();
  }
}
