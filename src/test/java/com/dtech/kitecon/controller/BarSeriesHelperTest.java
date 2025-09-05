package com.dtech.kitecon.controller;

import com.dtech.algo.runner.candle.DataTick;
import com.dtech.algo.runner.candle.LatestBarSeriesProvider;
import com.dtech.algo.series.Exchange;
import com.dtech.algo.series.InstrumentType;
import com.dtech.algo.series.Interval;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.strategy.config.BarSeriesConfig;
import com.dtech.kitecon.config.HistoricalDateLimit;
import com.dtech.kitecon.service.DatabaseBatchUpdateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class BarSeriesHelperTest {

    @Mock
    private HistoricalDateLimit historicalDateLimit;

    @Mock
    private LatestBarSeriesProvider barSeriesLoader;

    @Mock
    private DatabaseBatchUpdateService databaseBatchUpdateService;

    @Mock
    private IntervalBarSeries mockBarSeries;

    @InjectMocks
    private BarSeriesHelper barSeriesHelper;

    private DataTick testTick;
    private BarSeriesConfig testConfig;

    @BeforeEach
    void setUp() {
        // Set up test data
        testTick = new DataTick();
        testTick.setInstrumentToken(12345L);
        testTick.setLastTradedPrice(100.5);
        testTick.setVolumeTradedToday(1000);
        testTick.setTickTimestamp(new Date());

        testConfig = BarSeriesConfig.builder()
                .interval(Interval.OneMinute)
                .exchange(Exchange.NSE)
                .instrument("RELIANCE")
                .instrumentType(InstrumentType.EQ)
                .startDate(LocalDate.now().minusDays(30))
                .endDate(LocalDate.now())
                .name("RELIANCE_MINUTE")
                .build();

        // Mock the historicalDateLimit
        when(historicalDateLimit.getScreenerDuration(anyString(), any(Interval.class)))
                .thenReturn(30);
    }

    @Test
    void testCreateBarSeriesConfig() {
        // Test creating bar series config
        BarSeriesConfig config = barSeriesHelper.createBarSeriesConfig("RELIANCE", "MINUTE");

        assertNotNull(config);
        assertEquals("RELIANCE", config.getInstrument());
        assertEquals(Interval.OneMinute, config.getInterval());
        assertEquals(Exchange.NSE, config.getExchange());
        assertEquals("RELIANCE_MINUTE", config.getName());
    }

    @Test
    void testGetIntervalBarSeries() throws Exception {
        // Mock barSeriesLoader
        when(barSeriesLoader.loadBarSeries(any(BarSeriesConfig.class)))
                .thenReturn(mockBarSeries);

        IntervalBarSeries result = barSeriesHelper.getIntervalBarSeries("RELIANCE", "MINUTE");

        assertNotNull(result);
        verify(barSeriesLoader).loadBarSeries(any(BarSeriesConfig.class));
    }

    @Test
    void testProcessTick_Success() throws Exception {
        // Mock the config lookup
        when(barSeriesHelper.getConfigForInstrumentToken(testTick.getInstrumentToken()))
                .thenReturn(testConfig);

        // Mock barSeriesLoader update returning null (candle not complete)
        when(barSeriesLoader.updateBarSeries(eq(testTick), any(IntervalBarSeries.class)))
                .thenReturn(null);

        boolean result = barSeriesHelper.processTick(testTick);

        assertTrue(result);
        verify(barSeriesLoader).updateBarSeries(eq(testTick), any(IntervalBarSeries.class));
        verify(databaseBatchUpdateService, never()).addToQueue(any());
    }

    @Test
    void testProcessTick_CandleComplete() throws Exception {
        // Mock the config lookup
        when(barSeriesHelper.getConfigForInstrumentToken(testTick.getInstrumentToken()))
                .thenReturn(testConfig);

        // Mock barSeriesLoader update returning true (candle complete)
        when(barSeriesLoader.updateBarSeries(eq(testTick), any(IntervalBarSeries.class)))
                .thenReturn(null);

        boolean result = barSeriesHelper.processTick(testTick);

        assertTrue(result);
        verify(barSeriesLoader).updateBarSeries(eq(testTick), any(IntervalBarSeries.class));
        verify(databaseBatchUpdateService).addToQueue(any());
    }

    @Test
    void testProcessTick_ConfigNotFound() {
        // Mock the config lookup returning null
        when(barSeriesHelper.getConfigForInstrumentToken(testTick.getInstrumentToken()))
                .thenReturn(null);

        boolean result = barSeriesHelper.processTick(testTick);

        assertFalse(result);
        verify(barSeriesLoader, never()).updateBarSeries(any(), any());
    }

    @Test
    void testProcessTick_Exception() throws Exception {
        // Mock the config lookup
        when(barSeriesHelper.getConfigForInstrumentToken(testTick.getInstrumentToken()))
                .thenReturn(testConfig);

        // Mock barSeriesLoader throwing exception
        when(barSeriesLoader.updateBarSeries(any(), any()))
                .thenThrow(new RuntimeException("Test exception"));

        boolean result = barSeriesHelper.processTick(testTick);

        assertFalse(result);
        verify(databaseBatchUpdateService, never()).addToQueue(any());
    }

    @Test
    void testProcessTicks() {
        // Create multiple test ticks
        List<DataTick> ticks = new ArrayList<>();
        ticks.add(testTick);

        DataTick testTick2 = new DataTick();
        testTick2.setInstrumentToken(67890L);
        testTick2.setLastTradedPrice(200.5);
        testTick2.setVolumeTradedToday(2000);
        testTick2.setTickTimestamp(new Date());
        ticks.add(testTick2);

        // Mock processTick to succeed for the first tick and fail for the second
        doReturn(true).doReturn(false).when(barSeriesHelper).processTick(any(DataTick.class));

        int result = barSeriesHelper.processTicks(ticks);

        assertEquals(1, result); // Only one tick processed successfully
        verify(barSeriesHelper, times(2)).processTick(any(DataTick.class));
    }

    @Test
    void testRegisterInstrument() {
        long instrumentToken = 12345L;
        String instrument = "RELIANCE";
        List<String> intervals = List.of("MINUTE", "FIFTEEN_MINUTE");

        barSeriesHelper.registerInstrument(instrument, instrumentToken, intervals);

        // Verify config is created and cached for each interval
        BarSeriesConfig config = barSeriesHelper.getConfigForInstrumentToken(instrumentToken);
        assertNotNull(config);
        assertEquals(instrument, config.getInstrument());
    }
}
