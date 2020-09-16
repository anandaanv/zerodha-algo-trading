package com.dtech.kitecon.repository;
import com.dtech.kitecon.data.FifteenMinuteCandle;
import org.springframework.stereotype.Repository;

@Repository
public interface FiveMinuteCandleRepository extends BaseCandleRepository<FifteenMinuteCandle, Long> {
}


