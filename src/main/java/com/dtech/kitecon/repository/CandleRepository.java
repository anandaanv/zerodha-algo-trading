package com.dtech.kitecon.repository;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.data.Candle;
import com.dtech.kitecon.data.Instrument;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CandleRepository extends JpaRepository<Candle, Long> {

  List<Candle> findAllByInstrumentAndTimeframe(Instrument instrument, Interval interval);

  List<Candle> findAllByInstrumentAndTimeframeAndTimestampBetween(Instrument instrument, Interval interval, Instant startDate, Instant endDate);

  Candle findFirstByInstrumentAndTimeframeOrderByTimestampDesc(Instrument instrument, Interval interval);

  Candle findFirstByInstrumentAndTimeframeOrderByTimestamp(Instrument instrument, Interval interval);

  void deleteByInstrumentAndTimeframe(Instrument instrument, Interval interval);

  void deleteByInstrumentAndTimeframeAndTimestampBetween(Instrument instrument, Interval interval,
                                                         Instant startDate, Instant endDate);
}


