package com.dtech.kitecon.repository;

import com.dtech.kitecon.data.BaseCandle;
import com.dtech.kitecon.data.Instrument;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class CandleRepository {

  private final List<BaseCandleRepository> repositoryList;
  private Map<String, BaseCandleRepository> repositories;

  @PostConstruct
  public void initialise() {
    repositories = repositoryList.stream()
        .collect(Collectors.toMap(BaseCandleRepository::getInterval, t -> t));
  }

  private BaseCandleRepository getDelegate(String interval) {
    return repositories.get(interval);
  }

  public List<? extends BaseCandle> findAllByInstrument(String interval, Instrument instrument) {
    return getDelegate(interval).findAllByInstrument(instrument);
  }

  public BaseCandle findFirstByInstrumentOrderByTimestampDesc(String interval,
      Instrument instrument) {
    return getDelegate(interval).findFirstByInstrumentOrderByTimestampDesc(instrument);
  }

  public BaseCandle findFirstByInstrumentOrderByTimestamp(String interval, Instrument instrument) {
    return getDelegate(interval).findFirstByInstrumentOrderByTimestamp(instrument);
  }

  public void deleteByInstrument(String interval, Instrument instrument) {
    getDelegate(interval).deleteByInstrument(instrument);
  }

  public void deleteByInstrumentAndTimestampBetween(String interval, Instrument instrument,
      LocalDateTime startDate, LocalDateTime endDate) {
    getDelegate(interval).deleteByInstrumentAndTimestampBetween(instrument,
        startDate, endDate);
  }

  public void saveAll(String interval, List<BaseCandle> databaseCandles) {
    getDelegate(interval).saveAll(databaseCandles);
  }
}
