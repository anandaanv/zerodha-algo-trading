package com.dtech.kitecon.service;

import com.dtech.kitecon.data.BaseCandle;
import com.dtech.kitecon.data.DailyCandle;
import com.dtech.kitecon.data.FifteenMinuteCandle;
import com.dtech.kitecon.data.FiveMinuteCandle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.data.OneMinuteCandle;
import com.zerodhatech.models.HistoricalData;
import java.text.ParseException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class CandleFacade {

  public BaseCandle buildCandle(Instrument instrument, DateTimeFormatter dateFormat,
      HistoricalData candle,
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
      case "minute":
        return OneMinuteCandle.builder().build();
      default:
        throw new RuntimeException("Unknown interval");
    }
  }

  public List<BaseCandle> buildCandlesFromOLSHStream(String interval, DateTimeFormatter dateFormat,
      Instrument instrument, HistoricalData candles) {
    List<BaseCandle> databaseCandles = candles.dataArrayList.stream().map(candle ->
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

}
