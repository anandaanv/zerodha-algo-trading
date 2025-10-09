package com.dtech.algo.strategy.units;

import com.dtech.algo.series.Exchange;
import com.dtech.algo.series.ExtendedBarSeries;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.strategy.builder.cache.BarSeriesCache;
import com.dtech.algo.strategy.builder.ifc.BarSeriesLoader;
import com.dtech.algo.strategy.config.BarSeriesConfig;
import com.dtech.kitecon.data.Candle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.CandleRepository;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

@RequiredArgsConstructor
@Service
@Primary
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
        List<Candle> candles = candleRepository.findAllByInstrumentAndTimeframeAndTimestampBetween(
                instrument, barSeriesConfig.getInterval(),
                barSeriesConfig.getStartDate(),
                barSeriesConfig.getEndDate().plus(1, ChronoUnit.DAYS));
        IntervalBarSeries intervalBarSeries = getBarSeries(instrument, candles, barSeriesConfig);
        barSeriesCache.put(key, intervalBarSeries);
        return intervalBarSeries;
    }

    protected IntervalBarSeries getBarSeries(Instrument instrument, List<? extends Candle> candles, BarSeriesConfig barSeriesConfig) {
        candles.sort(Comparator.comparing(Candle::getTimestamp));
        BarSeries series = new BaseBarSeriesBuilder().withName(instrument.getTradingsymbol())
                .build();
        candles.forEach(candle -> addBarToSeries(series, candle));
        return ExtendedBarSeries.builder()
                .interval(barSeriesConfig.getInterval())
                .seriesType(barSeriesConfig.getSeriesType())
                .delegate(series)
                .instrument(barSeriesConfig.getInstrument())
                .build();
    }

    protected void addBarToSeries(BarSeries series, Candle candle) {
        Instant date = candle.getTimestamp();
        double open = candle.getOpen();
        double high = candle.getHigh();
        double low = candle.getLow();
        double close = candle.getClose();
        double volume = candle.getVolume();
        series.addBar(BarsLoader.getBar(open, high, low, close, volume, date));
    }

    public Instrument resolveInstrument(BarSeriesConfig barSeriesConfig) {
        List<Instrument> instruments = instrumentRepository.findAllByExchangeAndInstrumentTypeAndTradingsymbolStartingWith(
                barSeriesConfig.getExchange().name(),
                barSeriesConfig.getInstrumentType().name(),
                barSeriesConfig.getInstrument()
        );
        if(instruments.isEmpty()) {
            instruments = instrumentRepository
                    .findAllByTradingsymbol(barSeriesConfig.getInstrument());
        }
        return instruments.getFirst();
    }
}
