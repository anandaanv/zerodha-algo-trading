//package com.dtech.kitecon.repository;
//
//import com.dtech.kitecon.data.Candle;
//import com.dtech.kitecon.data.Instrument;
//import lombok.RequiredArgsConstructor;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Service;
//
//import jakarta.annotation.PostConstruct;
//import java.time.LocalDateTime;
//import java.util.List;
//import java.util.Map;
//import java.util.stream.Collectors;
//
//@SuppressWarnings("ALL")
//@Service
//@RequiredArgsConstructor(onConstructor = @__(@Autowired))
//public class CandleRepository {
//
//  private final List<BaseCandleRepository> repositoryList;
//  private Map<String, BaseCandleRepository> repositories;
//
//  @PostConstruct
//  public void initialise() {
//    repositories = repositoryList.stream()
//        .collect(Collectors.toMap(BaseCandleRepository::getInterval, t -> t));
//  }
//
//  public List<String> getAllIntervals() {
//    return repositories.keySet().stream().collect(Collectors.toList());
//  }
//
//  private BaseCandleRepository getDelegate(Interval interval) {
//    return repositories.get(interval);
//  }
//
//  public List<? extends Candle> findAllByInstrument(Interval interval, Instrument instrument) {
//    return getDelegate(interval).findAllByInstrument(instrument);
//  }
//
//  public Candle findFirstByInstrumentOrderByTimestampDesc(Interval interval,
//                                                          Instrument instrument) {
//    return getDelegate(interval).findFirstByInstrumentOrderByTimestampDesc(instrument);
//  }
//
//  public List<Candle> findAllByInstrumentAndTimestampBetween(Interval interval, Instrument instrument,
//                                                             LocalDateTime startDate, LocalDateTime endDate) {
//    return getDelegate(interval).findAllByInstrumentAndTimestampBetween(instrument, startDate, endDate);
//  }
//
//
//  public void deleteByInstrument(Interval interval, Instrument instrument) {
//    getDelegate(interval).deleteByInstrument(instrument);
//  }
//
//  public void deleteByInstrumentAndTimestampBetween(Interval interval, Instrument instrument,
//      LocalDateTime startDate, LocalDateTime endDate) {
//    getDelegate(interval).deleteByInstrumentAndTimestampBetween(instrument,
//        startDate, endDate);
//  }
//
//  public void saveAll(Interval interval, List<Candle> databaseCandles) {
//    getDelegate(interval).saveAll(databaseCandles);
//  }
//}
