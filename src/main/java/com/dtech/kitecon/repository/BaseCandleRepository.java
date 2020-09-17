package com.dtech.kitecon.repository;

import com.dtech.kitecon.data.BaseCandle;
import com.dtech.kitecon.data.Instrument;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

@NoRepositoryBean
public interface BaseCandleRepository<T extends BaseCandle, ID> extends JpaRepository<T, ID> {

  List<T> findAllByInstrument(Instrument instrument);

  T findFirstByInstrumentOrderByTimestampDesc(Instrument instrument);

  T findFirstByInstrumentOrderByTimestamp(Instrument instrument);

  void deleteByInstrument(Instrument instrument);

  void deleteByInstrumentAndTimestampBetween(Instrument instrument,
      LocalDateTime startDate, LocalDateTime endDate);

  String getInterval();
}


