package com.dtech.kitecon.service;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.config.KiteConnectConfig;
import com.dtech.kitecon.data.Candle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.CandleRepository;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;
import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Responsibility of this class is to download the data and insert in the database.
 */
@Component
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
@Log4j2
public class DataDownloader {

  private final KiteConnectConfig kiteConnectConfig;
  private final CandleRepository candleRepository;
  private final CandleFacade candleFacade;

  @Transactional
  public void processDownload(DataDownloadRequest downloadRequest)
      throws KiteException, IOException {
    log.info("Download data for " + downloadRequest);
    Interval interval = downloadRequest.getInterval();
    DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
    DateRange dateRange = downloadRequest.getDateRange();
    Instrument instrument = downloadRequest.getInstrument();
    ZonedDateTime startDate = dateRange.getStartDate().toLocalDate().atStartOfDay(ZoneId.systemDefault());
    ZonedDateTime endDate = dateRange.getEndDate().toLocalDate().atStartOfDay(ZoneId.systemDefault());
    candleRepository
        .deleteByInstrumentAndTimeframeAndTimestampBetween(instrument, interval,
            startDate.toLocalDateTime(),
            endDate.toLocalDateTime());
    HistoricalData candles = kiteConnectConfig.getKiteConnect().getHistoricalData(Date.from(
        startDate.toInstant()),
        Date.from(endDate.toInstant()),
        String.valueOf(instrument.getInstrumentToken()),
        interval.getKiteKey(), false, true);
    List<Candle> databaseCandles = candleFacade.buildCandlesFromOLSHStream(
        interval, dateFormat, instrument, candles);
    candleRepository.saveAll(databaseCandles);

  }

}
