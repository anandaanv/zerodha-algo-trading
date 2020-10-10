package com.dtech.algo.strategy.units;

import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.strategy.builder.ifc.BarSeriesLoader;
import com.dtech.algo.strategy.config.BarSeriesConfig;
import com.dtech.kitecon.data.BaseCandle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.market.fetch.DataFetchException;
import com.dtech.kitecon.repository.BaseCandleRepository;
import com.dtech.kitecon.repository.CandleRepository;
import com.dtech.kitecon.repository.InstrumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
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

    @Cacheable(key = "barSeriesConfig.getName")
    @Override
    public IntervalBarSeries loadBarSeries(BarSeriesConfig barSeriesConfig) {
        return null;
    }

    protected BarSeries getBarSeries(Instrument instrument, List<? extends BaseCandle> candles) {
        candles.sort(Comparator.comparing(BaseCandle::getTimestamp));
        BarSeries series = new BaseBarSeries(instrument.getTradingsymbol());
        candles.forEach(candle -> addBarToSeries(series, candle));
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

    public Instrument resolveInstrument(BarSeriesConfig barSeriesConfig) {
        return instrumentRepository.findAllByExchangeAndInstrumentTypeAndTradingsymbolStartingWith(
                barSeriesConfig.getExchange().name(),
                barSeriesConfig.getInstrumentType().name(),
                barSeriesConfig.getInstrument()
        ).get(0);
    }

    /**
     * @return a time series from Apple Inc. bars.
     */
    public BarSeries loadInstrumentSeries(Instrument instrument, ZonedDateTime startDate,
                                          String interval)
            throws DataFetchException {

        List<? extends BaseCandle> candles = candleRepository
                .findAllByInstrument(interval, instrument);
        return getBarSeries(instrument, candles);
    }

}
