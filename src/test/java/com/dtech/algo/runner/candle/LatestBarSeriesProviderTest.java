package com.dtech.algo.runner.candle;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.series.*;
import com.dtech.algo.strategy.builder.ifc.BarSeriesLoader;
import com.dtech.algo.strategy.config.BarSeriesConfig;
import com.dtech.kitecon.data.BaseCandle;
import com.dtech.kitecon.data.FifteenMinuteCandle;
import com.dtech.kitecon.data.Instrument;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class LatestBarSeriesProviderTest {

    @Mock
    private BarSeriesLoader delegate;

    @InjectMocks
    private LatestBarSeriesProvider barSeriesProvider;

    @Test
    void loadBarSeries() throws StrategyException {
        LocalDate date = LocalDate.now().minusDays(10);
        ExtendedBarSeries barSeries = new ExtendedBarSeries();
        Mockito.doReturn(barSeries)
                .when(delegate).loadBarSeries(Mockito.any(BarSeriesConfig.class));

        IntervalBarSeries intervalBarSeries = barSeriesProvider.loadBarSeries(getBarSeriesConfigSbinCash15Min(date, date));
        Mockito.verify(delegate, times(1))
                .loadBarSeries(ArgumentMatchers.argThat(argument ->
                        argument.getEndDate().equals(LocalDate.now())));
        assertEquals(barSeries, intervalBarSeries);

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

}