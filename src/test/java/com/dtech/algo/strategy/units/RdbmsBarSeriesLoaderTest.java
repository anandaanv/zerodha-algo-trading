package com.dtech.algo.strategy.units;

import com.dtech.algo.series.*;
import com.dtech.algo.strategy.builder.cache.BarSeriesCache;
import com.dtech.algo.strategy.config.BarSeriesConfig;
import com.dtech.kitecon.data.BaseCandle;
import com.dtech.kitecon.data.FifteenMinuteCandle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.CandleRepository;
import com.dtech.kitecon.repository.InstrumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class RdbmsBarSeriesLoaderTest {

    @Mock
    private InstrumentRepository instrumentRepository;

    @Mock
    private CandleRepository candleRepository;

    @Mock
    private BarSeriesCache barSeriesCache;

    @InjectMocks RdbmsBarSeriesLoader rdbmsBarSeriesLoader;

    @Test
    void loadBarSeries() {
        Instrument sbin = Instrument.builder()
                .name("SBIN").build();
        LocalDate date = LocalDate.now();
        LocalDateTime time = date.atStartOfDay();
        Mockito.doReturn(Collections.singletonList(get15MinCandle(sbin)))
                .when(candleRepository).findAllByInstrumentAndTimestampBetween("15minute", sbin, time, time.plusDays(1));
        Mockito.doReturn(Collections.singletonList(sbin))
                .when(instrumentRepository)
                .findAllByExchangeAndInstrumentTypeAndTradingsymbolStartingWith(Exchange.NSE.name(), InstrumentType.EQ.name(), "SBIN");
        IntervalBarSeries intervalBarSeries = rdbmsBarSeriesLoader.loadBarSeries(getBarSeriesConfigSbinCash15Min(date, date));
        assertEquals(intervalBarSeries.getInterval(), Interval.FifteenMinute);
        assertEquals(intervalBarSeries.getInstrument(), sbin.getName());
        assertEquals(intervalBarSeries.getSeriesType(), SeriesType.EQUITY);
        assertEquals(intervalBarSeries.getBarCount(), 1);
        assertEquals(intervalBarSeries.getBar(0).getHighPrice().doubleValue(), 100.0, 0.1);

    }

    private BaseCandle get15MinCandle(Instrument instrument) {
        return new FifteenMinuteCandle(100.0, 100.0, 92.0, 109.0, 10000,
                100, LocalDateTime.now(), instrument);
    }

    @Test
    void getBarSeries() {
    }

    @Test
    void addBarToSeries() {
    }

    @Test
    void resolveInstrument() {
        Instrument sbin = Instrument.builder()
                .name("SBIN").build();
        Mockito.doReturn(Collections.singletonList(sbin))
                .when(instrumentRepository)
                .findAllByExchangeAndInstrumentTypeAndTradingsymbolStartingWith(Exchange.NSE.name(), InstrumentType.EQ.name(), "SBIN");
        LocalDate currentDate = LocalDate.now();
        assertEquals(sbin, rdbmsBarSeriesLoader.resolveInstrument(
                getBarSeriesConfigSbinCash15Min(currentDate, currentDate)));
    }

    private BarSeriesConfig getBarSeriesConfigSbinCash15Min(LocalDate endDate, LocalDate startDate) {
        return BarSeriesConfig.builder()
                .seriesType(SeriesType.EQUITY)
                .exchange(Exchange.NSE)
                .instrument("SBIN")
                .instrumentType(InstrumentType.EQ)
                .interval(Interval.FifteenMinute)
                .name("sbin15min")
                .endDate(endDate)
                .startDate(startDate)
                .build();
    }

    @Test
    void loadInstrumentSeries() {
    }
}