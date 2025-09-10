package com.dtech.kitecon.service;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.data.*;
import com.zerodhatech.models.HistoricalData;
import org.springframework.stereotype.Component;
import org.ta4j.core.BaseBar;

import java.text.ParseException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class CandleFacade {

  public Candle buildCandle(Instrument instrument, DateTimeFormatter dateFormat,
                            HistoricalData candle,
                            Interval interval) throws ParseException {
    Candle dbCandle = new Candle();
    setupDbCandle(instrument, dateFormat, candle, interval, dbCandle);
    return dbCandle;
  }

  private Candle setupDbCandle(Instrument instrument, DateTimeFormatter dateFormat, HistoricalData candle, Interval interval, Candle dbCandle) {
    dbCandle.setTimeframe(interval);
    dbCandle.setInstrument(instrument);
    dbCandle.setOpen(candle.open);
    dbCandle.setHigh(candle.high);
    dbCandle.setLow(candle.low);
    dbCandle.setClose(candle.close);
    dbCandle.setVolume(candle.volume);
    dbCandle.setOi(candle.oi);
    dbCandle.setTimestamp(Instant.parse(candle.timeStamp));
    return dbCandle;
  }

  private static LocalDateTime getLocalDateTime(ZonedDateTime timeStamp) {
    return timeStamp.toLocalDateTime();
  }


  public Candle buildCandle(Instrument instrument, BaseBar baseBar,
                            Interval interval) {
    Candle dbCandle = new Candle();
    setupCandle(instrument, baseBar, interval, dbCandle);
    return dbCandle;
  }

  private static void setupCandle(Instrument instrument, BaseBar baseBar, Interval interval, Candle dbCandle) {
    dbCandle.setTimeframe(interval);
    dbCandle.setInstrument(instrument);
    dbCandle.setOpen(baseBar.getOpenPrice().doubleValue());
    dbCandle.setHigh(baseBar.getHighPrice().doubleValue());
    dbCandle.setLow(baseBar.getLowPrice().doubleValue());
    dbCandle.setClose(baseBar.getClosePrice().doubleValue());
    dbCandle.setVolume(baseBar.getVolume().longValue());
    dbCandle.setOi(0L);
    dbCandle.setTimestamp(baseBar.getEndTime());
  }

  public List<Candle> buildCandlesFromOLSHStream(Interval interval, DateTimeFormatter dateFormat,
                                                 Instrument instrument, HistoricalData candles) {
    List<Candle> databaseCandles = candles.dataArrayList.stream().map(candle ->
    {
      try {
        return this.buildCandle(instrument, dateFormat, candle,
            interval);
      } catch (ParseException e) {
        e.printStackTrace();
        return null;
      }
    })
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
    return databaseCandles;
  }

  public List<Candle> buildCandlesFromOLSHStreamFailSafe(Interval interval, DateTimeFormatter dateFormat,
                                                         Instrument instrument, HistoricalData candles, Map<Instant, Candle> datamap) {
      return candles.dataArrayList.stream().map(candle ->
            {
              try {
                Instant localDateTime = Instant.parse(candle.timeStamp);
                if(datamap.containsKey(localDateTime)) {
                  return setupDbCandle(instrument, dateFormat, candle, interval, datamap.get(localDateTime));
                } else {
                  Candle returnCandle = this.buildCandle(instrument, dateFormat, candle,
                          interval);
                  datamap.put(localDateTime, returnCandle);
                  return returnCandle;
                }
              } catch (ParseException e) {
                e.printStackTrace();
                return null;
              }
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
  }

}
