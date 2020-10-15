package com.dtech.algo.strategy.units;

import com.dtech.algo.series.ExtendedBarSeries;
import com.dtech.algo.series.Interval;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.strategy.builder.cache.BarSeriesCache;
import com.dtech.algo.strategy.builder.ifc.BarSeriesLoader;
import com.dtech.algo.strategy.config.BarSeriesConfig;
import com.dtech.kitecon.data.BaseCandle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.CandleRepository;
import com.dtech.kitecon.repository.InstrumentRepository;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;

@RequiredArgsConstructor
@Service
public class RdbmsBarSeriesLoader implements BarSeriesLoader {

    private final CandleRepository candleRepository;
    private final InstrumentRepository instrumentRepository;
    private final BarSeriesCache barSeriesCache;

    @Override
    public IntervalBarSeries loadBarSeries(BarSeriesConfig barSeriesConfig) {
        String key = barSeriesConfig.getName();
        IntervalBarSeries barSeries = barSeriesCache.get(key);
        if(barSeries != null) {
            return barSeries;
        }
        Instrument instrument = resolveInstrument(barSeriesConfig);
        List<BaseCandle> candles = candleRepository.findAllByInstrumentAndTimestampBetween(getIntervalMapping(barSeriesConfig),
                instrument,
                barSeriesConfig.getStartDate().atStartOfDay(),
                barSeriesConfig.getEndDate().plusDays(1).atStartOfDay());
        IntervalBarSeries intervalBarSeries = getBarSeries(instrument, candles, barSeriesConfig);
        barSeriesCache.put(key, intervalBarSeries);
        return intervalBarSeries;
    }

    @NotNull
    private String getIntervalMapping(BarSeriesConfig barSeriesConfig) {
        Interval interval = barSeriesConfig.getInterval();
        switch (interval) {
            case Day:
                return "day";
            case FifteenMinute:
                return "15minute";
            case FiveMinute:
                return "5minute";
            case OneMinute:
                return "minute";
            default:
                return interval.name().toLowerCase();
        }
    }

    protected IntervalBarSeries getBarSeries(Instrument instrument, List<? extends BaseCandle> candles, BarSeriesConfig barSeriesConfig) {
        candles.sort(Comparator.comparing(BaseCandle::getTimestamp));
        BarSeries series = new BaseBarSeries(instrument.getTradingsymbol());
        candles.forEach(candle -> addBarToSeries(series, candle));
        return ExtendedBarSeries.builder()
                .interval(barSeriesConfig.getInterval())
                .seriesType(barSeriesConfig.getSeriesType())
                .delegate(series)
                .instrument(barSeriesConfig.getInstrument())
                .build();
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

    public Instrument resolveInstrument(BarSeriesConfig barSeriesConfig) {
        return instrumentRepository.findAllByExchangeAndInstrumentTypeAndTradingsymbolStartingWith(
                barSeriesConfig.getExchange().name(),
                barSeriesConfig.getInstrumentType().name(),
                barSeriesConfig.getInstrument()
        ).get(0);
    }
}
