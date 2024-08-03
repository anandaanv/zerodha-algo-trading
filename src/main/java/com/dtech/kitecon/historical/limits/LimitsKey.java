package com.dtech.kitecon.historical.limits;

import com.dtech.algo.series.Interval;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@AllArgsConstructor
@Data
@Builder
public class LimitsKey {

  private String exchange;
  private String interval;

}
