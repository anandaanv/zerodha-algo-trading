package com.dtech.kitecon.service;

import com.dtech.kitecon.config.KiteConnectConfig;
import com.dtech.kitecon.data.BaseCandle;
import com.dtech.kitecon.data.DailyCandle;
import com.dtech.kitecon.data.FifteenMinuteCandle;
import com.dtech.kitecon.data.FiveMinuteCandle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.BaseCandleRepository;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;
import java.io.IOException;
import java.text.ParseException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
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

  @Transactional
  public void processDownload(BaseCandleRepository candleRepository, DataDownloadRequest downloadRequest)
      throws KiteException, IOException {
    log.info("Download data for " + downloadRequest);
    DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
    DateRange dateRange = downloadRequest.getDateRange();
    candleRepository.deleteByInstrumentAndTimestampBetween(downloadRequest.getInstrument(),
        dateRange.getStartDate().toLocalDateTime(),
        dateRange.getEndDate().toLocalDateTime());
    HistoricalData candles = kiteConnectConfig.getKiteConnect().getHistoricalData(Date.from(
        dateRange.getStartDate().toInstant()),
        Date.from(dateRange.getEndDate().toInstant()),
        String.valueOf(downloadRequest.getInstrument().getInstrument_token()),
        downloadRequest.getInterval(), false, true);
    List<BaseCandle> databaseCandles = candles.dataArrayList.stream().map(candle ->
    {
      try {
        return buildCandle(downloadRequest.getInstrument(), dateFormat, candle, downloadRequest.getInterval());
      } catch (ParseException e) {
        e.printStackTrace();
        return null;
      }
    })
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
    candleRepository.saveAll(databaseCandles);


  }

  private BaseCandle buildCandle(Instrument instrument, DateTimeFormatter dateFormat, HistoricalData candle,
      String interval) throws ParseException {
    BaseCandle dbCandle = getBaseCandle(interval);
    dbCandle.setInstrument(instrument);
    dbCandle.setOpen(candle.open);
    dbCandle.setHigh(candle.high);
    dbCandle.setLow(candle.low);
    dbCandle.setClose(candle.close);
    dbCandle.setVolume(candle.volume);
    dbCandle.setOi(candle.oi);
    dbCandle.setTimestamp(ZonedDateTime.parse(candle.timeStamp, dateFormat).toLocalDateTime());
    return dbCandle;
  }

  private BaseCandle getBaseCandle(String interval) {
    switch (interval) {
      case "day":
        return DailyCandle.builder().build();
      case "15minute":
        return FifteenMinuteCandle.builder().build();
      case "5minute":
        return FiveMinuteCandle.builder().build();
      default: throw new RuntimeException("Unknown interval");
    }
  }

}
