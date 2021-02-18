package com.dtech.algo.series;

import com.dtech.algo.strategy.sync.CandleSyncExecutor;
import com.google.common.base.CharMatcher;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;

import java.time.ZoneId;
import java.time.ZonedDateTime;

@ExtendWith(MockitoExtension.class)
class ExtendedBarSeriesTest {

    @Spy
    private BarSeries delegate = new BaseBarSeries();

    @Spy
    private Interval interval = Interval.FifteenMinute;
    @Mock
    private SeriesType seriesType;

    private String instrument = null;

    @Mock
    private CandleSyncExecutor candleSyncExecutor;

    @Test
    void addBarWithTimeValidationWithin15Mins() {
        ExtendedBarSeries extendedBarSeries = new ExtendedBarSeries(delegate, interval, seriesType, instrument, null);
        ZonedDateTime time = ZonedDateTime.of(2020, 12, 12, 12,
                12, 0, 0, ZoneId.systemDefault());
        extendedBarSeries.addBarWithTimeValidation(time, 1.0, 2.0, 3.0, 4.0, 5.0);
        Assertions.assertEquals(extendedBarSeries.getBar(0).getEndTime(), time.plusMinutes(3));
    }

    @Test
    void addBarWithTimeValidationWithin1Min() {
        ExtendedBarSeries extendedBarSeries = new ExtendedBarSeries(delegate, Interval.OneMinute, seriesType, instrument, null);
        ZonedDateTime time = ZonedDateTime.of(2020, 12, 12, 12,
                12, 0, 0, ZoneId.systemDefault());
        extendedBarSeries.addBarWithTimeValidation(time, 1.0, 2.0, 3.0, 4.0, 5.0);
        Assertions.assertEquals(extendedBarSeries.getBar(0).getEndTime(), time.plusMinutes(1));
    }

    @Test
    void addBarWithTimeValidationWithin1Hour() {
        ExtendedBarSeries extendedBarSeries = new ExtendedBarSeries(delegate, Interval.OneHour, seriesType, instrument, null);
        ZonedDateTime time = ZonedDateTime.of(2020, 12, 12, 12,
                12, 0, 0, ZoneId.systemDefault());
        extendedBarSeries.addBarWithTimeValidation(time, 1.0, 2.0, 3.0, 4.0, 5.0);
        Assertions.assertEquals(extendedBarSeries.getBar(0).getEndTime(), time.plusMinutes(48));
    }

    @Test
    void addTwoBarsForSameTimeframeAndMakeSureItWorks() {
        ExtendedBarSeries extendedBarSeries = new ExtendedBarSeries(delegate, Interval.OneHour, seriesType, instrument, null);
        ZonedDateTime time = ZonedDateTime.of(2020, 12, 12, 12,
                12, 0, 0, ZoneId.systemDefault());
        extendedBarSeries.addBarWithTimeValidation(time, 1.0, 2.0, 3.0, 4.0, 5.0);
        extendedBarSeries.addBarWithTimeValidation(time.plusSeconds(10), 1.0, 2.0, 3.0, 4.0, 5.0);
        Assertions.assertEquals(extendedBarSeries.getBar(0).getEndTime(), time.plusMinutes(48));
    }

    @Test
    void addTwoBarsForDifferentTimeframeAndMakeSureItWorks() {
        ExtendedBarSeries extendedBarSeries = new ExtendedBarSeries(delegate, Interval.FifteenMinute, seriesType, instrument, null);
        ZonedDateTime time = ZonedDateTime.of(2020, 12, 12, 12,
                12, 0, 0, ZoneId.systemDefault());
        extendedBarSeries.addBarWithTimeValidation(time, 1.0, 2.0, 3.0, 4.0, 5.0);
        extendedBarSeries.addBarWithTimeValidation(time.plusMinutes(15), 1.0, 2.0, 3.0, 4.0, 5.0);
        Assertions.assertEquals(extendedBarSeries.getBar(0).getEndTime(), time.plusMinutes(3));
        Assertions.assertEquals(extendedBarSeries.getBar(1).getEndTime(), time.plusMinutes(18));
    }

    @Test
    void addTwoBarsForDifferentTimeframeAndMakeSureTheSyncIsExecuted() {
        ExtendedBarSeries extendedBarSeries = new ExtendedBarSeries(delegate, Interval.FifteenMinute, seriesType, instrument, candleSyncExecutor);
        ZonedDateTime time = ZonedDateTime.of(2020, 12, 12, 12,
                12, 0, 0, ZoneId.systemDefault());
        extendedBarSeries.addBarWithTimeValidation(time, 1.0, 2.0, 3.0, 4.0, 5.0);
        extendedBarSeries.addBarWithTimeValidation(time.plusMinutes(15), 1.0, 2.0, 3.0, 4.0, 5.0);
        Assertions.assertEquals(extendedBarSeries.getBar(0).getEndTime(), time.plusMinutes(3));
        Assertions.assertEquals(extendedBarSeries.getBar(1).getEndTime(), time.plusMinutes(18));
        Mockito.verify(candleSyncExecutor, Mockito.times(1)).submit(Mockito.any());
    }

}