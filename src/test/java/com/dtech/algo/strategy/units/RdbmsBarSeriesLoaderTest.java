package com.dtech.algo.strategy.units;

import com.dtech.algo.series.Exchange;
import com.dtech.algo.series.InstrumentType;
import com.dtech.algo.series.Interval;
import com.dtech.algo.series.SeriesType;
import com.dtech.algo.strategy.config.BarSeriesConfig;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class RdbmsBarSeriesLoaderTest {

    @Mock
    private InstrumentRepository instrumentRepository;

    @InjectMocks RdbmsBarSeriesLoader rdbmsBarSeriesLoader;

    @Test
    void loadBarSeries() {
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
        assertEquals(sbin, rdbmsBarSeriesLoader.resolveInstrument(
                getBarSeriesConfigSbinCash15Min()));
    }

    private BarSeriesConfig getBarSeriesConfigSbinCash15Min() {
        return BarSeriesConfig.builder()
                .seriesType(SeriesType.EQUITY)
                .exchange(Exchange.NSE)
                .instrument("SBIN")
                .instrumentType(InstrumentType.EQ)
                .interval(Interval.FifteenMinute)
                .name("sbin15min")
                .build();
    }

    @Test
    void loadInstrumentSeries() {
    }
}