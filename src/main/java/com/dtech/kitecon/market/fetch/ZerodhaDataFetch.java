package com.dtech.kitecon.market.fetch;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.data.Candle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.market.facade.MarketException;
import com.dtech.kitecon.market.facade.MarketFacade;
import com.dtech.kitecon.market.facade.MarketFacadeProvider;
import com.dtech.kitecon.service.CandleFacade;
import com.dtech.kitecon.service.DateRange;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.LTPQuote;
import com.zerodhatech.models.Profile;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor(onConstructor = @__(@Autowired))
@Component
public class ZerodhaDataFetch implements MarketDataFetch{

  private final MarketFacadeProvider marketFacadeProvider;
  private final CandleFacade candleFacade;

  DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");

  @Override
  public String getProfile() throws DataFetchException {
    try {
      MarketFacade facade = marketFacadeProvider.getFacade();
      Profile profile = facade.getProfile();
      return profile.userName;
    } catch (MarketException e) {
      throw new DataFetchException(e);
    }
  }

  @Override
  public List<Candle> fetch(DateRange dateRange, String instrumentToken, Interval interval)
      throws DataFetchException {
      return fetch(dateRange, instrumentToken, interval, false);
  }

    @Override
    public List<Candle> fetch(DateRange dateRange, String instrumentToken, Interval interval, boolean continuous)
            throws DataFetchException {
        try {
            MarketFacade facade = marketFacadeProvider.getFacade();
            HistoricalData candles = facade.getHistoricalData(Date.from(
                            dateRange.getStartDate()),
                    Date.from(dateRange.getEndDate()),
                    instrumentToken,
                    interval.getKiteKey(), continuous, true);
            List<Candle> baseCandles = candleFacade.buildCandlesFromOLSHStream(interval, dateFormat, // reuse existing parser
              // create an Instrument placeholder with only token if needed (caller passes instrument separately),
              // but this method signature receives only token; we will let callers call the other fetch variant
              // that also passes an Instrument where needed. To keep minimal change, we parse without instrument-specific fields.
              new Instrument() {{
                setInstrumentToken(Long.parseLong(instrumentToken));
              }},
              candles);
      return baseCandles;
    } catch (Throwable e) {
      throw new DataFetchException(e);
    }
  }

  @Override
  public List<Candle> fetchTodaysData(Instrument instrument, Interval interval)
      throws DataFetchException {
    try {
      MarketFacade facade = marketFacadeProvider.getFacade();
      ZonedDateTime now = ZonedDateTime.now();
      ZonedDateTime startDate = now.toLocalDate().atStartOfDay(now.getZone());
      ZonedDateTime endDate = now;
      HistoricalData candles = facade.getHistoricalData(Date.from(
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

    public Double getLastPrice(Instrument instrument) throws DataFetchException {
        try {
            MarketFacade facade = marketFacadeProvider.getFacade();
            String instrumentToken = "" + instrument.getInstrumentToken();
            Map<String, LTPQuote> ltp = facade.getLTP(new String[]{instrumentToken});
            return ltp.get(instrumentToken).lastPrice;
        } catch (Throwable e) {
            throw new DataFetchException(e);
        }
    }


}
