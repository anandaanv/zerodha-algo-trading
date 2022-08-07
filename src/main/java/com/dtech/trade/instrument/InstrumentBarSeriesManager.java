package com.dtech.trade.instrument;

import com.dtech.algo.series.ExtendedBarSeries;
import com.dtech.algo.series.Interval;
import com.dtech.algo.series.SeriesType;
import com.dtech.kitecon.data.BaseCandle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.CandleRepository;
import lombok.RequiredArgsConstructor;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.dtech.algo.series.Interval.*;

@RequiredArgsConstructor
public class InstrumentBarSeriesManager {
    private final Instrument instrument;
    private final CandleRepository candleRepository;

    final Map<String, List<? extends BaseCandle>> candleMap = new HashMap<>();
    final Map<String, BarSeries> barSeriesMap = new HashMap<>();


    public void initialize() {
        candleRepository.getAllIntervals().forEach(
                interval -> {
                    List<? extends BaseCandle> allCandles = candleRepository.findAllByInstrument(interval,
                            instrument);
                    candleMap.put(interval, allCandles);
                    BarSeries series = new BaseBarSeries(instrument.getTradingsymbol());
                    ExtendedBarSeries barSeries = ExtendedBarSeries.builder()
                            .interval(getIntervalByName(interval))
                            .seriesType(SeriesType.EQUITY) // FIXME Hardcode it for now. we will soon need to make it
                            // read from Instrument.
                            .delegate(series)
                            .instrument(instrument.getTradingsymbol())
                            .build();
                    allCandles.forEach(baseCandle -> addBarToSeries(barSeries, baseCandle));
                    barSeriesMap.put(interval, barSeries);
                });
    }

    protected void addBarToSeries(ExtendedBarSeries series, BaseCandle candle) {
        ZonedDateTime date = ZonedDateTime.of(candle.getTimestamp(), ZoneId.systemDefault());
        double open = candle.getOpen();
        double high = candle.getHigh();
        double low = candle.getLow();
        double close = candle.getClose();
        double volume = candle.getVolume();
        series.addBar(date, open, high, low, close, volume);
    }

    Interval getIntervalByName(String name) {
        switch (name) {
            case "1minute":
                return OneMinute;
            case "1Hour":
                return OneHour;
            case "5minute":
                return FiveMinute;
            case "15minute":
                return FifteenMinute;
            case "30minute":
                return ThirtyMinute;
            case "3minute":
                return ThreeMinute;
            default:
                return Interval.valueOf(name);
        }
    }
}
