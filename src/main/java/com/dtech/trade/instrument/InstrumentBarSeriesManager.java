package com.dtech.trade.instrument;

import com.dtech.algo.series.ExtendedBarSeries;
import com.dtech.algo.series.Interval;
import com.dtech.algo.series.SeriesType;
import com.dtech.kitecon.data.Candle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.CandleRepository;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import lombok.RequiredArgsConstructor;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.dtech.algo.series.Interval.*;

@RequiredArgsConstructor
public class InstrumentBarSeriesManager {
    private final Instrument instrument;
    private final CandleRepository candleRepository;

    final Map<Interval, List<Candle>> candleMap = new HashMap<>();
    final Map<Interval, BarSeries> barSeriesMap = new HashMap<>();


    public void initialize() {
        Arrays.stream(values()).forEach(
                interval -> {
                    List<Candle> allCandles = candleRepository.findAllByInstrumentAndTimeframe(instrument, interval);
                    candleMap.put(interval, allCandles);
                    BarSeries series = new BaseBarSeriesBuilder().withName(instrument.getTradingsymbol()).build();
                    ExtendedBarSeries barSeries = ExtendedBarSeries.builder()
                            .interval(getIntervalByName(interval.getKiteKey()))
                            .seriesType(SeriesType.EQUITY) // FIXME Hardcode it for now. we will soon need to make it
                            // read from Instrument.
                            .delegate(series)
                            .instrument(instrument.getTradingsymbol())
                            .build();
                    allCandles.forEach(baseCandle -> addBarToSeries(barSeries, baseCandle));
                    barSeriesMap.put(interval, barSeries);
                });
    }

    protected void addBarToSeries(ExtendedBarSeries series, Candle candle) {
        Instant date = candle.getTimestamp();
        double open = candle.getOpen();
        double high = candle.getHigh();
        double low = candle.getLow();
        double close = candle.getClose();
        double volume = candle.getVolume();
        series.addBar(BarsLoader.getBar(open, high, low, close, volume, date));
    }

    Interval getIntervalByName(String name) {
        switch (name) {
            case "minute":
                return OneMinute;
            case "hour":
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
