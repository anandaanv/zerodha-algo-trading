/*******************************************************************************
 *   The MIT License (MIT)
 *
 *   Copyright (c) 2014-2017 Marc de Verdelhan, 2017-2018 Ta4j Organization 
 *   & respective authors (see AUTHORS)
 *
 *   Permission is hereby granted, free of charge, to any person obtaining a copy of
 *   this software and associated documentation files (the "Software"), to deal in
 *   the Software without restriction, including without limitation the rights to
 *   use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 *   the Software, and to permit persons to whom the Software is furnished to do so,
 *   subject to the following conditions:
 *
 *   The above copyright notice and this permission notice shall be included in all
 *   copies or substantial portions of the Software.
 *
 *   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *   IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 *   FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 *   COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 *   IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 *   CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *******************************************************************************/
package com.dtech.kitecon.strategy.dataloader;

import com.dtech.kitecon.data.BaseCandle;
import com.dtech.kitecon.data.FifteenMinuteCandle;
import com.dtech.kitecon.data.FiveMinuteCandle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.market.fetch.DataFetchException;
import com.dtech.kitecon.repository.CandleRepository;
import com.dtech.kitecon.repository.FifteenMinuteCandleRepository;
import com.dtech.kitecon.repository.FiveMinuteCandleRepository;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;

/**
 * This class build a Ta4j time series from a CSV file containing bars.
 */
@Component
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public abstract class BarsLoader {

  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
  private final CandleRepository fifteenMinuteCandleRepository;

  /**
   * @return a time series from Apple Inc. bars.
   */

  public BarSeries loadInstrumentSeries(Instrument instrument) {

    List<? extends BaseCandle> candles = fifteenMinuteCandleRepository
        .findAllByInstrument("15minute", instrument);

    return getBarSeries(instrument, candles);
  }

  protected BarSeries getBarSeries(Instrument instrument, List<? extends BaseCandle> candles) {
    candles.sort(Comparator.comparing(BaseCandle::getTimestamp));
    BarSeries series = new BaseBarSeries(instrument.getTradingsymbol());
    candles.forEach(candle -> {
      addBarToSeries(series, candle);
    });
    return series;
  }

  protected void addBarToSeries(BarSeries series, BaseCandle candle) {
    ZonedDateTime date = ZonedDateTime.of(candle.getTimestamp(), ZoneId.systemDefault());
    double open = candle.getOpen();
    double high = candle.getHigh();
    double low = candle.getLow();
    double close = candle.getClose();
    double volume = candle.getVolume();
    series.addBar(date, open, high, low, close, volume);
  }

  /**
   * @return a time series from Apple Inc. bars.
   */
  public BarSeries loadInstrumentSeries(Instrument instrument, ZonedDateTime startDate)
      throws DataFetchException {

    List<? extends BaseCandle> candles = fifteenMinuteCandleRepository
        .findAllByInstrument("15minute", instrument);
    return getBarSeries(instrument, candles);
  }

}
