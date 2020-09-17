package com.dtech.kitecon.repository;

import com.dtech.kitecon.data.FifteenMinuteCandle;
import org.springframework.stereotype.Repository;

@Repository
public interface FifteenMinuteCandleRepository extends
    BaseCandleRepository<FifteenMinuteCandle, Long> {

  @Override
  default String getInterval() {
    return "15minute";
  }

}


