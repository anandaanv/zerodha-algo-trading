package com.dtech.algo.runner.candle;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.series.*;
import com.dtech.algo.strategy.builder.ifc.BarSeriesLoader;
import com.dtech.algo.strategy.config.BarSeriesConfig;
import com.dtech.kitecon.KiteconApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
@SpringBootTest(classes = KiteconApplication.class)
class LatestBarSeriesProviderTest {

    @MockBean
    private BarSeriesLoader delegate;

    @SpyBean
    private LatestBarSeriesProvider barSeriesProvider;

    @Test
    void loadBarSeries() throws StrategyException {
        Instant date = Instant.now().minus(10, ChronoUnit.DAYS);
        ExtendedBarSeries barSeries = new ExtendedBarSeries();
        Mockito.doReturn(barSeries)
                .when(delegate).loadBarSeries(Mockito.any(BarSeriesConfig.class));

        IntervalBarSeries intervalBarSeries = barSeriesProvider.loadBarSeries(getBarSeriesConfigSbinCash15Min(date, date));
        Mockito.verify(delegate, times(1))
                .loadBarSeries(ArgumentMatchers.argThat(argument ->
                        argument.getEndDate().equals(LocalDate.now())));
        assertEquals(barSeries, intervalBarSeries);

    }

    private BarSeriesConfig getBarSeriesConfigSbinCash15Min(Instant endDate, Instant startDate) {
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