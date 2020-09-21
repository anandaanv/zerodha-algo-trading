package com.dtech.kitecon.service;

import com.dtech.kitecon.config.KiteConnectConfig;
import com.dtech.kitecon.data.BaseCandle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.CandleRepository;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import javax.transaction.Transactional;
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
    String interval = downloadRequest.getInterval();
    DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
    DateRange dateRange = downloadRequest.getDateRange();
    Instrument instrument = downloadRequest.getInstrument();
    candleRepository
        .deleteByInstrumentAndTimestampBetween(interval, instrument,
            dateRange.getStartDate().toLocalDateTime(),
            dateRange.getEndDate().toLocalDateTime());
    HistoricalData candles = kiteConnectConfig.getKiteConnect().getHistoricalData(Date.from(
        dateRange.getStartDate().toInstant()),
        Date.from(dateRange.getEndDate().toInstant()),
        String.valueOf(instrument.getInstrument_token()),
        interval, false, true);
    List<BaseCandle> databaseCandles = candleFacade.buildCandlesFromOLSHStream(
        interval, dateFormat, instrument, candles);
    candleRepository.saveAll(interval, databaseCandles);

  }

}
