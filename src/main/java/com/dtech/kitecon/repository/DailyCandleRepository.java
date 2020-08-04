package com.dtech.kitecon.repository;
import com.dtech.kitecon.data.DailyCandle;
import com.dtech.kitecon.data.FifteenMinuteCandle;
import org.springframework.stereotype.Repository;

@Repository
public interface DailyCandleRepository extends BaseCandleRepository<DailyCandle, Long> {
}


