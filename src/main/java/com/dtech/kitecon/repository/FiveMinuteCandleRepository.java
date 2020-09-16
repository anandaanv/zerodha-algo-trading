package com.dtech.kitecon.repository;

import com.dtech.kitecon.data.FiveMinuteCandle;
import org.springframework.stereotype.Repository;

@Repository
public interface FiveMinuteCandleRepository extends BaseCandleRepository<FiveMinuteCandle, Long> {
}


