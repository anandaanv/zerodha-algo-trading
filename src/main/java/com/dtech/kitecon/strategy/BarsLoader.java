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
package com.dtech.kitecon.strategy;

import com.dtech.kitecon.data.FifteenMinuteCandle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.FifteenMinuteCandleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.ta4j.core.BaseTimeSeries;
import org.ta4j.core.TimeSeries;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/**
 * This class build a Ta4j time series from a CSV file containing bars.
 */
@Component
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class BarsLoader {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private final FifteenMinuteCandleRepository fifteenMinuteCandleRepository;

    /**
     * @return a time series from Apple Inc. bars.
     */

    public TimeSeries loadInstrumentSeries(Instrument instrument) {

        List<FifteenMinuteCandle> candles = fifteenMinuteCandleRepository.findAllByInstrument(instrument);

        candles.sort(Comparator.comparing(FifteenMinuteCandle::getTimestamp));

        TimeSeries series = new BaseTimeSeries(instrument.getTradingsymbol());
        candles.forEach(candle -> {
            ZonedDateTime date = ZonedDateTime.ofInstant(candle.getTimestamp().toInstant(),
                    ZoneId.systemDefault());
            double open = candle.getOpen();
            double high = candle.getHigh();
            double low = candle.getLow();
            double close = candle.getClose();
            double volume = candle.getVolume();

            series.addBar(date, open, high, low, close, volume);
        });
        return series;
    }

}
