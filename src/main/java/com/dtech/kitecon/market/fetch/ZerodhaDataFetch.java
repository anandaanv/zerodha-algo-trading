package com.dtech.kitecon.market.fetch;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.config.KiteConnectConfig;
import com.dtech.kitecon.data.Candle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.service.CandleFacade;
import com.dtech.kitecon.service.DateRange;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.Profile;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor(onConstructor = @__(@Autowired))
@Component
public class ZerodhaDataFetch implements MarketDataFetch{

  private final KiteConnectConfig kiteConnectConfig;
  private final CandleFacade candleFacade;

  DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");

  @Override
  public String getProfile() throws IOException, KiteException {
    Profile profile = kiteConnectConfig.getKiteConnect().getProfile();
    return profile.userName;
  }

  @Override
  public void fetch(DateRange dateRange, String instrumentToken, Interval interval)
      throws DataFetchException {
    try {
      HistoricalData candles = kiteConnectConfig.getKiteConnect().getHistoricalData(Date.from(
          dateRange.getStartDate()),
          Date.from(dateRange.getEndDate()),
          instrumentToken,
          interval.getKiteKey(), false, true);
    } catch (Throwable e) {
      throw new DataFetchException(e);
    }
  }

  @Override
  public List<Candle> fetchTodaysData(Instrument instrument, Interval interval)
      throws DataFetchException {
    try {
      ZonedDateTime now = ZonedDateTime.now();
      ZonedDateTime startDate = now.toLocalDate().atStartOfDay(now.getZone());
      ZonedDateTime endDate = now;
      HistoricalData candles = kiteConnectConfig.getKiteConnect().getHistoricalData(Date.from(
          startDate.toInstant()),
          Date.from(endDate.toInstant()),
          String.valueOf(instrument.getInstrumentToken()),
          interval.getKiteKey(), false, true);
      List<Candle> baseCandles = candleFacade
          .buildCandlesFromOLSHStream(interval, dateFormat, instrument, candles);
      return baseCandles;
    } catch (Throwable e) {
      throw new DataFetchException(e);
    }
  }

}
