package com.dtech.kitecon.patternscanner;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.backtest.DetectedPattern;
import com.dtech.kitecon.backtest.PatternComboBacktestService;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.service.DataFetchService;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.chartpattern.zigzag.ZigZagParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.num.DecimalNum;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PatternScanServiceTest {

    @Mock
    private PatternComboBacktestService patternComboBacktestService;

    @Mock
    private ZigZagService zigZagService;

    @Mock
    private InstrumentRepository instrumentRepository;

    @Mock
    private DataFetchService dataFetchService;

    @InjectMocks
    private PatternScanService patternScanService;

    private Instrument mockInstrument;
    private BarSeries mockBarSeries;

    @BeforeEach
    void setUp() {
        mockInstrument = new Instrument();
        mockInstrument.setTradingsymbol("HDFCBANK");
        mockInstrument.setExchange("NSE");

        // Create a mock BarSeries with at least one bar
        mockBarSeries = createMockBarSeries(5);
    }

    /**
     * Test 1: scan() fetches instrument and refreshes candle data
     */
    @Test
    void testScan_FetchesInstrumentAndRefreshesCandleData() {
        // Arrange
        String symbol = "HDFCBANK";
        when(instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"}))
                .thenReturn(mockInstrument);
        when(zigZagService.getBarSeries(anyString(), any(), any(Interval.class)))
                .thenReturn(mockBarSeries);
        when(zigZagService.resolveParams(anyString(), any(Interval.class)))
                .thenReturn(ZigZagParams.ofDefaults(14, 1.0, 0.5, 0.1, 2, false, 1.0, 10, ZigZagParams.Mode.LIVE));
        when(zigZagService.detect(eq(mockBarSeries), any(ZigZagParams.class)))
                .thenReturn(new ArrayList<>());
        mockComputeMethods();

        // Act
        PatternScanResultDto result = patternScanService.scan(symbol, Interval.OneHour, Interval.FifteenMinute);

        // Assert
        assertNotNull(result);
        verify(dataFetchService, times(3)).updateInstrumentToLatest(eq(symbol), any(Interval.class), eq(new String[]{"NSE"}));
        verify(instrumentRepository).findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"});
    }

    /**
     * Test 2: scan() throws exception when bar series is null/empty
     */
    @Test
    void testScan_ThrowsExceptionWhenBarSeriesEmpty() {
        // Arrange
        String symbol = "HDFCBANK";
        when(instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"}))
                .thenReturn(mockInstrument);
        when(zigZagService.getBarSeries(symbol, mockInstrument, Interval.OneHour))
                .thenReturn(null);

        // Act & Assert
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> patternScanService.scan(symbol, Interval.OneHour, Interval.FifteenMinute));
        assertTrue(ex.getMessage().contains("No watching TF data"));
    }

    /**
     * Test 3: scan() detects ZigZag pivots from bar series
     */
    @Test
    void testScan_DetectsZigZagPivots() {
        // Arrange
        String symbol = "HDFCBANK";
        Instant pivotTime = Instant.now();
        ZigZagPoint mockPivot = createMockZigZagPoint(pivotTime, 1500.0, true);

        when(instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"}))
                .thenReturn(mockInstrument);
        when(zigZagService.getBarSeries(anyString(), any(), any(Interval.class)))
                .thenReturn(mockBarSeries);
        when(zigZagService.resolveParams(anyString(), any(Interval.class)))
                .thenReturn(ZigZagParams.ofDefaults(14, 1.0, 0.5, 0.1, 2, false, 1.0, 10, ZigZagParams.Mode.LIVE));
        when(zigZagService.detect(eq(mockBarSeries), any(ZigZagParams.class)))
                .thenReturn(List.of(mockPivot));
        mockComputeMethods();

        // Act
        PatternScanResultDto result = patternScanService.scan(symbol, Interval.OneHour, Interval.FifteenMinute);

        // Assert
        assertNotNull(result);
        verify(zigZagService).detect(eq(mockBarSeries), any(ZigZagParams.class));
    }

    /**
     * Test 4: scan() runs pattern scanners on pivots
     */
    @Test
    void testScan_RunsPatternScannersOnPivots() {
        // Arrange
        String symbol = "HDFCBANK";
        List<ZigZagPoint> pivots = List.of(
                createMockZigZagPoint(Instant.now().minusSeconds(3600), 1500.0, true),
                createMockZigZagPoint(Instant.now(), 1510.0, false)
        );

        when(instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"}))
                .thenReturn(mockInstrument);
        when(zigZagService.getBarSeries(anyString(), any(), any(Interval.class)))
                .thenReturn(mockBarSeries);
        when(zigZagService.resolveParams(anyString(), any(Interval.class)))
                .thenReturn(ZigZagParams.ofDefaults(14, 1.0, 0.5, 0.1, 2, false, 1.0, 10, ZigZagParams.Mode.LIVE));
        when(zigZagService.detect(eq(mockBarSeries), any(ZigZagParams.class)))
                .thenReturn(pivots);
        when(patternComboBacktestService.scanDtbWatchingPublic(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ArrayList<>());
        when(patternComboBacktestService.scanTriangleWatchingPublic(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ArrayList<>());
        when(patternComboBacktestService.scanHnsWatchingPublic(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ArrayList<>());
        when(patternComboBacktestService.scanFlagWatchingPublic(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ArrayList<>());
        when(patternComboBacktestService.scanTrendlineBreakoutWatchingPublic(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ArrayList<>());
        mockComputeMethods();

        // Act
        PatternScanResultDto result = patternScanService.scan(symbol, Interval.OneHour, Interval.FifteenMinute);

        // Assert
        assertNotNull(result);
        verify(patternComboBacktestService).scanDtbWatchingPublic(any(), any(), any(), any(), any(), any(), any());
        verify(patternComboBacktestService).scanTriangleWatchingPublic(any(), any(), any(), any(), any(), any(), any());
        verify(patternComboBacktestService).scanHnsWatchingPublic(any(), any(), any(), any(), any(), any(), any());
        verify(patternComboBacktestService).scanFlagWatchingPublic(any(), any(), any(), any(), any(), any(), any());
        verify(patternComboBacktestService).scanTrendlineBreakoutWatchingPublic(any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * Test 5: scan() computes indicators (ATR, RSI, MACD, StochRSI)
     */
    @Test
    void testScan_ComputesIndicators() {
        // Arrange
        String symbol = "HDFCBANK";
        when(instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"}))
                .thenReturn(mockInstrument);
        when(zigZagService.getBarSeries(anyString(), any(), any(Interval.class)))
                .thenReturn(mockBarSeries);
        when(zigZagService.resolveParams(anyString(), any(Interval.class)))
                .thenReturn(ZigZagParams.ofDefaults(14, 1.0, 0.5, 0.1, 2, false, 1.0, 10, ZigZagParams.Mode.LIVE));
        when(zigZagService.detect(eq(mockBarSeries), any(ZigZagParams.class)))
                .thenReturn(new ArrayList<>());
        mockComputeMethods();

        // Act
        PatternScanResultDto result = patternScanService.scan(symbol, Interval.OneHour, Interval.FifteenMinute);

        // Assert
        assertNotNull(result);
        verify(patternComboBacktestService).computeAtrPublic(any(), eq(14));
        verify(patternComboBacktestService).computeRsiPublic(any(BarSeries.class), eq(14));
        verify(patternComboBacktestService).computeMacdHistPublic(any(BarSeries.class));
        verify(patternComboBacktestService).computeStochRsiKPublic(any());
    }

    /**
     * Test 6: scan() throws exception when instrument not found
     */
    @Test
    void testScan_ThrowsExceptionWhenInstrumentNotFound() {
        // Arrange
        String symbol = "INVALID_SYMBOL";
        when(instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"}))
                .thenReturn(null);

        // Act & Assert
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> patternScanService.scan(symbol, Interval.OneHour, Interval.FifteenMinute));
        assertTrue(ex.getMessage().contains("Instrument not found"));
    }

    /**
     * Test: getNifty50() returns non-empty list
     */
    @Test
    void testGetNifty50() {
        List<String> nifty50 = patternScanService.getNifty50();
        assertNotNull(nifty50);
        assertFalse(nifty50.isEmpty());
        assertTrue(nifty50.contains("RELIANCE"));
        assertTrue(nifty50.contains("HDFCBANK"));
    }

    /**
     * Test: getFnoSymbols() delegates to repository
     */
    @Test
    void testGetFnoSymbols() {
        // Arrange
        List<String> fnoSymbols = List.of("RELIANCE", "TCS", "HDFCBANK");
        when(instrumentRepository.findDistinctFutureUnderlyingNamesWithNseEquity())
                .thenReturn(fnoSymbols);

        // Act
        List<String> result = patternScanService.getFnoSymbols();

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertTrue(result.contains("RELIANCE"));
        verify(instrumentRepository).findDistinctFutureUnderlyingNamesWithNseEquity();
    }

    // ==================== Helper Methods ====================

    private BarSeries createMockBarSeries(int barCount) {
        BarSeries series = new BaseBarSeriesBuilder().withName("TEST").build();

        Instant now = Instant.now();
        for (int i = 0; i < barCount; i++) {
            Instant time = now.minusSeconds((barCount - i) * 3600L);
            // Using mock bar to avoid complex BaseBar constructor
            org.ta4j.core.Bar bar = mock(org.ta4j.core.Bar.class);
            when(bar.getEndTime()).thenReturn(time);
            when(bar.getOpenPrice()).thenReturn(DecimalNum.valueOf(1500 + i * 5));
            when(bar.getHighPrice()).thenReturn(DecimalNum.valueOf(1510 + i * 5));
            when(bar.getLowPrice()).thenReturn(DecimalNum.valueOf(1490 + i * 5));
            when(bar.getClosePrice()).thenReturn(DecimalNum.valueOf(1505 + i * 5));
            when(bar.getVolume()).thenReturn(DecimalNum.valueOf(10000));
            series.addBar(bar);
        }
        return series;
    }

    private ZigZagPoint createMockZigZagPoint(Instant timestamp, double price, boolean isHigh) {
        ZigZagPoint point = mock(ZigZagPoint.class);
        when(point.getTimestamp()).thenReturn(timestamp);
        when(point.getValue()).thenReturn(price);
        when(point.isHigh()).thenReturn(isHigh);
        when(point.isLow()).thenReturn(!isHigh);
        return point;
    }

    private void mockComputeMethods() {
        // Mock ATR array
        double[] atrArr = new double[]{14.0, 14.5, 15.0, 15.5, 16.0};
        when(patternComboBacktestService.computeAtrPublic(any(), eq(14)))
                .thenReturn(atrArr);

        // Mock RSI array
        double[] rsiArr = new double[]{50.0, 52.0, 54.0, 56.0, 58.0};
        when(patternComboBacktestService.computeRsiPublic(any(BarSeries.class), eq(14)))
                .thenReturn(rsiArr);

        // Mock MACD histogram array
        double[] macdHistArr = new double[]{0.1, 0.2, 0.3, 0.4, 0.5};
        when(patternComboBacktestService.computeMacdHistPublic(any(BarSeries.class)))
                .thenReturn(macdHistArr);

        // Mock StochRSI K array
        double[] stochRsiArr = new double[]{40.0, 45.0, 50.0, 55.0, 60.0};
        when(patternComboBacktestService.computeStochRsiKPublic(any()))
                .thenReturn(stochRsiArr);

        // Mock DailyIndicators (always empty/zero values)
        PatternComboBacktestService.DailyIndicators mockIndicators = mock(PatternComboBacktestService.DailyIndicators.class);
        when(mockIndicators.adxAtTs(any())).thenReturn(0.0);
        when(mockIndicators.adxEmaAtTs(any())).thenReturn(0.0);
        when(mockIndicators.macdLineAtTs(any())).thenReturn(0.0);
        when(mockIndicators.macdSignalAtTs(any())).thenReturn(0.0);
        when(mockIndicators.bbWidthAtTs(any())).thenReturn(0.0);
        when(mockIndicators.bbPctBAtTs(any())).thenReturn(0.0);
        when(mockIndicators.rsiAtTs(any())).thenReturn(0.0);
        when(mockIndicators.bbExpandingAtTs(any(), anyInt())).thenReturn(0.0);
        when(mockIndicators.bbAlignedAtTs(any(), anyInt(), anyBoolean())).thenReturn(0.0);
        when(mockIndicators.rsiSlopeAtTs(any(), anyInt())).thenReturn(0.0);
        when(mockIndicators.macdHistSlopeAtTs(any(), anyInt())).thenReturn(0.0);
        when(mockIndicators.adxSlopeAtTs(any(), anyInt())).thenReturn(0.0);

        when(patternComboBacktestService.computeDailyIndicators(any()))
                .thenReturn(mockIndicators);
    }
}
