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
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;

@SpringBootTest(classes = KiteconApplication.class)
class LatestBarSeriesProviderFromCacheTest {

    @MockBean
    private BarSeriesLoader delegate;

    @SpyBean
    private LatestBarSeriesProvider barSeriesProvider;

    @Test
    @DirtiesContext
    void loadBarSeriesFromCache() throws StrategyException {
        LocalDate date = LocalDate.now().minusDays(10);
        ExtendedBarSeries barSeries = new ExtendedBarSeries();
        Mockito.doReturn(barSeries)
                .when(delegate).loadBarSeries(Mockito.any(BarSeriesConfig.class));

        IntervalBarSeries intervalBarSeries = barSeriesProvider.loadBarSeries(getBarSeriesConfigSbinCash15Min(date, date));
        IntervalBarSeries intervalBarSeries2 = barSeriesProvider.loadBarSeries(getBarSeriesConfigSbinCash15Min(date, date));
        assertEquals(barSeries, intervalBarSeries2);
        assertEquals(intervalBarSeries, intervalBarSeries2);
        Mockito.verify(delegate, atLeast(1))
                .loadBarSeries(ArgumentMatchers.argThat(argument ->
                        argument.getEndDate().equals(LocalDate.now())));
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