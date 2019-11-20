package com.dtech.kitecon.repository;
import com.dtech.kitecon.data.FifteenMinuteCandle;
import com.dtech.kitecon.data.Instrument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FifteenMinuteCandleRepository extends BaseCandleRepository<FifteenMinuteCandle, Long> {
}


