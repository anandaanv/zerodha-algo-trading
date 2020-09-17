package com.dtech.kitecon.repository;

import com.dtech.kitecon.data.DailyCandle;
import com.dtech.kitecon.data.OneMinuteCandle;
import org.springframework.stereotype.Repository;

@Repository
public interface OneMinuteCandleRepository extends BaseCandleRepository<OneMinuteCandle, Long> {

  @Override
  default String getInterval() {
    return "minute";
  }
}


