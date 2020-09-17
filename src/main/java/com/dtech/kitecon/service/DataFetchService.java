package com.dtech.kitecon.service;

import com.dtech.kitecon.config.HistoricalDateLimit;
import com.dtech.kitecon.config.KiteConnectConfig;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Profile;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
@Log4j2
public class DataFetchService {

  private final InstrumentRepository instrumentRepository;
  private final KiteConnectConfig kiteConnectConfig;
  private final DataDownloader dataDownloader;
  private final HistoricalDateLimit historicalDateLimit;

  public String getProfile() throws IOException, KiteException {
    Profile profile = kiteConnectConfig.getKiteConnect().getProfile();
    return profile.userName;
  }

  public void downloadAllInstruments() throws KiteException, IOException {
    Map<Long, Instrument> databaseInstruments = instrumentRepository.findAll()
        .stream()
        .collect(Collectors.toMap(Instrument::getInstrument_token, instrument -> instrument));
    List<com.zerodhatech.models.Instrument> instruments = kiteConnectConfig.getKiteConnect()
        .getInstruments();
    List<Instrument> newInstruments = instruments.stream()
        .filter(instrument -> !databaseInstruments.containsKey(instrument.instrument_token))
        .map(instrument -> Instrument.builder()
            .instrument_token(instrument.instrument_token)
            .instrument_type(instrument.instrument_type)
            .strike(instrument.strike)
            .exchange_token(instrument.exchange_token)
            .tradingsymbol(instrument.tradingsymbol)
            .name(instrument.name)
            .last_price(instrument.last_price)
            .expiry(instrument.getExpiry() == null ? null : dateToLocalDate(instrument))
            .tick_size(instrument.tick_size)
            .lot_size(instrument.lot_size)
            .instrument_type(instrument.instrument_type)
            .segment(instrument.segment)
            .exchange(instrument.exchange)
            .build()).collect(Collectors.toList());
    instrumentRepository.saveAll(newInstruments);
  }

  private LocalDateTime dateToLocalDate(com.zerodhatech.models.Instrument instrument) {
    return LocalDateTime.ofInstant(instrument.expiry.toInstant(),
        ZoneId.systemDefault());
  }

  public void downloadCandleData(String instrumentName, String interval, String[] exchanges) {
    LocalDateTime startDate = LocalDateTime.now().minus(4, ChronoUnit.MONTHS);
    List<Instrument> primaryInstruments = getInstrumentList(instrumentName, exchanges, startDate);
    primaryInstruments.forEach(instrument -> this.downloadData(instrument, interval));
  }

  private List<Instrument> getInstrumentList(String instrumentName, String[] exchanges,
      LocalDateTime startDate) {
    List<Instrument> instruments = instrumentRepository
        .findAllByTradingsymbolStartingWithAndExpiryBetweenAndExchangeIn(
            instrumentName, LocalDateTime.now().plusDays(60), startDate, exchanges);
    List<Instrument> primaryInstruments = instrumentRepository
        .findAllByTradingsymbolStartingWithAndExpiryIsNullAndExchangeIn(
            instrumentName, exchanges);
    instruments.addAll(primaryInstruments);
    instruments.addAll(primaryInstruments);
    return primaryInstruments;
  }

  public void downloadData(Instrument instrument, String interval) {
    ZonedDateTime endTime = ZonedDateTime.now();
    int totalAvailableDuration = historicalDateLimit
        .getTotalAvailableDuration(instrument.getExchange(), interval);
    ZonedDateTime startTime = endTime.minusDays(totalAvailableDuration);
    int sliceSize = historicalDateLimit.getDuration(instrument.getExchange(), interval);
    List<DateRange> dateRangeList = DateRange.builder()
        .endDate(endTime)
        .startDate(startTime)
        .build()
        .split(sliceSize);
    dateRangeList.forEach(dateRange -> {
      DataDownloadRequest dataDownloadRequest = DataDownloadRequest.builder()
          .dateRange(dateRange)
          .instrument(instrument)
          .interval(interval)
          .build();
      try {
        dataDownloader.processDownload(dataDownloadRequest);
      } catch (Throwable e) {
        log.catching(e);
      }
    });
  }
}
