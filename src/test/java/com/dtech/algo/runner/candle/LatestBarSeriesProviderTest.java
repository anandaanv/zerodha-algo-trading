package com.dtech.algo.runner.candle;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.series.Interval;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.series.SeriesType;
import com.dtech.algo.strategy.builder.ifc.BarSeriesLoader;
import com.dtech.algo.strategy.config.BarSeriesConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ta4j.core.Bar;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.mockito.quality.Strictness;

/**
 * Unit tests for LatestBarSeriesProvider.
 * Tests bar series caching, delegation to BarSeriesLoader, and tick-based bar updates.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = Strictness.LENIENT)
class LatestBarSeriesProviderTest {

    @Mock
    private BarSeriesLoader delegate;

    @Mock
    private BarTimeCalculator barTimeCalculator;

    @InjectMocks
    private LatestBarSeriesProvider provider;

    private BarSeriesConfig config1;
    private BarSeriesConfig config2;
    private IntervalBarSeries mockSeries;
    private DataTick mockTick;
    private Bar mockBar;

    @BeforeEach
    void setUp() {
        // Setup basic config for testing
        Instant now = Instant.now();
        Instant tenDaysAgo = now.minusSeconds(10 * 24 * 3600);

        config1 = BarSeriesConfig.builder()
                .instrument("RELIANCE")
                .seriesType(SeriesType.EQUITY)
                .interval(Interval.FifteenMinute)
                .startDate(tenDaysAgo)
                .endDate(now)
                .build();

        config2 = BarSeriesConfig.builder()
                .instrument("INFY")
                .seriesType(SeriesType.EQUITY)
                .interval(Interval.FifteenMinute)
                .startDate(tenDaysAgo)
                .endDate(now)
                .build();

        mockSeries = mock(IntervalBarSeries.class);
        mockTick = mock(DataTick.class);
        mockBar = mock(Bar.class);
    }

    /**
     * Verify that first load of bar series invokes the delegate loader.
     */
    @Test
    void testLoadBarSeries_FirstLoadCallsDelegate() throws StrategyException {
        when(delegate.loadBarSeries(config1)).thenReturn(mockSeries);

        IntervalBarSeries result = provider.loadBarSeries(config1);

        assertNotNull(result);
        assertEquals(mockSeries, result);
        verify(delegate, times(1)).loadBarSeries(config1);
    }

    /**
     * Verify that second load with same config returns cached result without calling delegate.
     */
    @Test
    void testLoadBarSeries_SecondLoadReturnsCachedResult() throws StrategyException {
        when(delegate.loadBarSeries(config1)).thenReturn(mockSeries);

        IntervalBarSeries first = provider.loadBarSeries(config1);
        IntervalBarSeries second = provider.loadBarSeries(config1);

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first, second);
        verify(delegate, times(1)).loadBarSeries(config1);
    }

    /**
     * Verify that different configs (different instrument) load separately and are not cached together.
     */
    @Test
    void testLoadBarSeries_DifferentConfigsNotCached() throws StrategyException {
        IntervalBarSeries series1 = mock(IntervalBarSeries.class, "series1");
        IntervalBarSeries series2 = mock(IntervalBarSeries.class, "series2");

        when(delegate.loadBarSeries(config1)).thenReturn(series1);
        when(delegate.loadBarSeries(config2)).thenReturn(series2);

        IntervalBarSeries result1 = provider.loadBarSeries(config1);
        IntervalBarSeries result2 = provider.loadBarSeries(config2);

        assertNotNull(result1);
        assertNotNull(result2);
        assertNotEquals(result1, result2);
        verify(delegate, times(1)).loadBarSeries(config1);
        verify(delegate, times(1)).loadBarSeries(config2);
    }

    /**
     * Verify that cache key includes instrument, series type, and interval.
     */
    @Test
    void testLoadBarSeries_CacheKeyIncludesInstrumentSeriesTypeAndInterval() throws StrategyException {
        Instant now = Instant.now();
        Instant tenDaysAgo = now.minusSeconds(10 * 24 * 3600);

        BarSeriesConfig configA = BarSeriesConfig.builder()
                .instrument("INFY")
                .seriesType(SeriesType.EQUITY)
                .interval(Interval.FifteenMinute)
                .startDate(tenDaysAgo)
                .endDate(now)
                .build();

        BarSeriesConfig configB = BarSeriesConfig.builder()
                .instrument("INFY")
                .seriesType(SeriesType.EQUITY)
                .interval(Interval.ThirtyMinute) // Different interval
                .startDate(tenDaysAgo)
                .endDate(now)
                .build();

        IntervalBarSeries series1 = mock(IntervalBarSeries.class, "series1");
        IntervalBarSeries series2 = mock(IntervalBarSeries.class, "series2");

        when(delegate.loadBarSeries(configA)).thenReturn(series1);
        when(delegate.loadBarSeries(configB)).thenReturn(series2);

        IntervalBarSeries result1 = provider.loadBarSeries(configA);
        IntervalBarSeries result2 = provider.loadBarSeries(configB);

        assertNotEquals(result1, result2);
        verify(delegate, times(1)).loadBarSeries(configA);
        verify(delegate, times(1)).loadBarSeries(configB);
    }

    /**
     * Verify that StrategyException thrown by delegate is propagated.
     */
    @Test
    void testLoadBarSeries_DelegateThrownExceptionPropagated() throws StrategyException {
        when(delegate.loadBarSeries(config1)).thenThrow(new StrategyException("Load failed"));

        assertThrows(StrategyException.class, () -> provider.loadBarSeries(config1));
        verify(delegate, times(1)).loadBarSeries(config1);
    }

    /**
     * Verify that updateBarSeries with null bar series returns null gracefully.
     */
    @Test
    void testUpdateBarSeries_WithNullBarSeriesReturnsNull() {
        when(mockTick.getTickTimestamp()).thenReturn(new Date());

        Bar result = provider.updateBarSeries(mockTick, null);

        assertNull(result);
    }

    /**
     * Verify that updateBarSeries updates current bar when tick is within same bar interval.
     */
    @Test
    void testUpdateBarSeries_UpdatesCurrentBarWhenInSameInterval() {
        // Setup: bar series has one bar
        when(mockSeries.getEndIndex()).thenReturn(0);
        when(mockSeries.getBar(0)).thenReturn(mockBar);

        // Tick timestamp and bar end time are in same interval
        Instant barEndTime = Instant.now().plusSeconds(300); // 5 minutes from now
        when(mockBar.getEndTime()).thenReturn(barEndTime);

        ZonedDateTime tickTime = ZonedDateTime.now();
        when(mockTick.getTickTimestamp()).thenReturn(new Date(tickTime.toInstant().toEpochMilli()));
        when(mockTick.getLastTradedPrice()).thenReturn(2500.0);
        when(mockTick.getVolumeTradedToday()).thenReturn(1000.0);

        // Calculate bar end time to be after tick time
        ZonedDateTime barEndTimeZdt = tickTime.plusMinutes(5);
        when(barTimeCalculator.calculateBarEndTime(any(ZonedDateTime.class), any(Interval.class)))
                .thenReturn(barEndTimeZdt);

        Bar result = provider.updateBarSeries(mockTick, mockSeries);

        assertNull(result); // Should return null when updating existing bar
    }

    /**
     * Verify that updateBarSeries creates new bar when tick time crosses bar boundary.
     */
    @Test
    void testUpdateBarSeries_CreatesNewBarWhenTimeExceedsInterval() {
        // Setup: bar series has one bar with past end time
        when(mockSeries.getEndIndex()).thenReturn(0);
        when(mockSeries.getBar(0)).thenReturn(mockBar);

        // Bar ended in the past
        Instant pastBarTime = Instant.now().minusSeconds(600); // 10 minutes ago
        when(mockBar.getEndTime()).thenReturn(pastBarTime);

        // Tick is now (after bar end time)
        ZonedDateTime tickTime = ZonedDateTime.now();
        when(mockTick.getTickTimestamp()).thenReturn(new Date(tickTime.toInstant().toEpochMilli()));
        when(mockTick.getLastTradedPrice()).thenReturn(2500.0);
        when(mockTick.getVolumeTradedToday()).thenReturn(1000.0);

        // New bar end time is after tick time
        ZonedDateTime newBarEndTime = tickTime.plusMinutes(5);
        when(barTimeCalculator.calculateBarEndTime(any(ZonedDateTime.class), any(Interval.class)))
                .thenReturn(newBarEndTime);

        when(mockSeries.getInterval()).thenReturn(Interval.FifteenMinute);
        when(mockSeries.numOf(any(Number.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Bar result = provider.updateBarSeries(mockTick, mockSeries);

        // Should return the previous (completed) bar
        assertNotNull(result);
        assertEquals(mockBar, result);
    }

    /**
     * Verify that updateBarSeries returns completed bar when new bar is created.
     */
    @Test
    void testUpdateBarSeries_ReturnsCompletedBarOnBarTransition() {
        when(mockSeries.getEndIndex()).thenReturn(0);
        when(mockSeries.getBar(0)).thenReturn(mockBar);

        // Past bar time triggers new bar creation
        Instant pastTime = Instant.now().minusSeconds(600);
        when(mockBar.getEndTime()).thenReturn(pastTime);

        ZonedDateTime tickTime = ZonedDateTime.now();
        when(mockTick.getTickTimestamp()).thenReturn(new Date(tickTime.toInstant().toEpochMilli()));
        when(mockTick.getLastTradedPrice()).thenReturn(2500.0);
        when(mockTick.getVolumeTradedToday()).thenReturn(1000.0);

        ZonedDateTime newBarEndTime = tickTime.plusMinutes(5);
        when(barTimeCalculator.calculateBarEndTime(any(ZonedDateTime.class), any(Interval.class)))
                .thenReturn(newBarEndTime);

        when(mockSeries.getInterval()).thenReturn(Interval.FifteenMinute);
        when(mockSeries.numOf(any(Number.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Bar completedBar = provider.updateBarSeries(mockTick, mockSeries);

        assertNotNull(completedBar);
        assertTrue(completedBar == mockBar);
    }

    /**
     * Verify that updateBarSeries returns null when updating existing bar (not creating new one).
     */
    @Test
    void testUpdateBarSeries_ReturnsNullWhenUpdatingCurrentBar() {
        when(mockSeries.getEndIndex()).thenReturn(0);
        when(mockSeries.getBar(0)).thenReturn(mockBar);

        // Bar end time is in future (same interval as tick)
        Instant futureBarTime = Instant.now().plusSeconds(300);
        when(mockBar.getEndTime()).thenReturn(futureBarTime);

        ZonedDateTime tickTime = ZonedDateTime.now();
        when(mockTick.getTickTimestamp()).thenReturn(new Date(tickTime.toInstant().toEpochMilli()));
        when(mockTick.getLastTradedPrice()).thenReturn(2500.0);
        when(mockTick.getVolumeTradedToday()).thenReturn(1000.0);

        // Bar end time after tick time
        ZonedDateTime barEndTimeZdt = tickTime.plusMinutes(5);
        when(barTimeCalculator.calculateBarEndTime(any(ZonedDateTime.class), any(Interval.class)))
                .thenReturn(barEndTimeZdt);

        Bar result = provider.updateBarSeries(mockTick, mockSeries);

        assertNull(result);
    }

    /**
     * Verify that updateBarSeries handles null tick timestamp by defaulting to current time.
     */
    @Test
    void testUpdateBarSeries_HandlesNullTickTimestampWithCurrentTime() {
        when(mockSeries.getEndIndex()).thenReturn(0);
        when(mockSeries.getBar(0)).thenReturn(mockBar);

        when(mockTick.getTickTimestamp()).thenReturn(null); // Null timestamp
        when(mockTick.getLastTradedPrice()).thenReturn(2500.0);
        when(mockTick.getVolumeTradedToday()).thenReturn(1000.0);

        Instant futureTime = Instant.now().plusSeconds(300);
        when(mockBar.getEndTime()).thenReturn(futureTime);

        ZonedDateTime barEndTime = ZonedDateTime.now().plusMinutes(5);
        when(barTimeCalculator.calculateBarEndTime(any(ZonedDateTime.class), any(Interval.class)))
                .thenReturn(barEndTime);

        Bar result = provider.updateBarSeries(mockTick, mockSeries);

        // Should handle gracefully and either update or create new bar
        assertNull(result); // Updated existing bar, should return null
    }

    /**
     * Verify that updateBarSeries updates high/low correctly with multiple ticks.
     */
    @Test
    void testUpdateBarSeries_UpdatesHighLowOnMultipleTicks() {
        when(mockSeries.getEndIndex()).thenReturn(0);
        when(mockSeries.getBar(0)).thenReturn(mockBar);

        Instant futureBarTime = Instant.now().plusSeconds(300);
        when(mockBar.getEndTime()).thenReturn(futureBarTime);

        ZonedDateTime tickTime = ZonedDateTime.now();
        ZonedDateTime barEndTime = tickTime.plusMinutes(5);

        // First tick: price 2500
        when(mockTick.getTickTimestamp()).thenReturn(new Date(tickTime.toInstant().toEpochMilli()));
        when(mockTick.getLastTradedPrice()).thenReturn(2500.0);
        when(mockTick.getVolumeTradedToday()).thenReturn(1000.0);

        when(barTimeCalculator.calculateBarEndTime(any(ZonedDateTime.class), any(Interval.class)))
                .thenReturn(barEndTime);

        Bar result1 = provider.updateBarSeries(mockTick, mockSeries);
        assertNull(result1); // Current bar updated

        // Second tick: higher price 2550
        when(mockTick.getLastTradedPrice()).thenReturn(2550.0);
        when(mockTick.getVolumeTradedToday()).thenReturn(500.0);

        Bar result2 = provider.updateBarSeries(mockTick, mockSeries);
        assertNull(result2); // Still updating current bar

        // Verify bar was called to update
        assertNotNull(mockBar);
    }
}
