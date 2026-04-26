package com.dtech.algo.service;

import com.dtech.algo.controller.dto.TradingViewChartRequest;
import com.dtech.algo.controller.dto.TradingViewChartResponse;
import com.dtech.algo.series.Interval;
import com.dtech.kitecon.controller.BarSeriesHelper;
import com.dtech.kitecon.auth.ServiceTokenHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradingViewChartServiceTest {

    @Mock
    private BarSeriesHelper barSeriesHelper;

    @Mock
    private ServiceTokenHolder serviceTokenHolder;

    @InjectMocks
    private TradingViewChartService chartService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(chartService, "chartsOutputDirectory", "/tmp/test-charts");
        ReflectionTestUtils.setField(chartService, "chartsTempDirectory", "/tmp/test-charts/temp");
        ReflectionTestUtils.setField(chartService, "browserPoolSize", 2);
        ReflectionTestUtils.setField(chartService, "browserTimeoutSeconds", 30);
        ReflectionTestUtils.setField(chartService, "frontendChartUrl", "http://localhost:5173");
    }

    @Test
    void testGenerateTradingViewChartsFromUrl_Success() {
        TradingViewChartRequest request = TradingViewChartRequest.builder()
                .symbol("HDFCBANK")
                .timeframes(Arrays.asList(Interval.FifteenMinute, Interval.OneHour))
                .candleCount(100)
                .layout("1x2")
                .showVolume(true)
                .build();

        when(serviceTokenHolder.asQueryParam()).thenReturn("?token=test");

        TradingViewChartResponse response = chartService.generateTradingViewChartsFromUrl(request);

        assertNotNull(response);
        assertEquals("HDFCBANK", response.getSymbol());
    }

    @Test
    void testGenerateTradingViewChartsFromUrl_EmptyTimeframes() {
        TradingViewChartRequest request = TradingViewChartRequest.builder()
                .symbol("INFY")
                .timeframes(Arrays.asList())
                .candleCount(50)
                .layout("1x1")
                .build();

        TradingViewChartResponse response = chartService.generateTradingViewChartsFromUrl(request);

        assertNotNull(response);
        assertEquals("INFY", response.getSymbol());
    }

    @Test
    void testGenerateTradingViewChartsFromUrl_SingleTimeframe() {
        TradingViewChartRequest request = TradingViewChartRequest.builder()
                .symbol("TCS")
                .timeframes(Arrays.asList(Interval.OneHour))
                .candleCount(75)
                .layout("1x1")
                .build();

        TradingViewChartResponse response = chartService.generateTradingViewChartsFromUrl(request);

        assertNotNull(response);
        assertEquals("TCS", response.getSymbol());
    }

    @Test
    void testGenerateChartUrl_SingleTimeframe() {
        when(serviceTokenHolder.asQueryParam()).thenReturn("?token=abc123");

        String url = chartService.generateChartUrl("RELIANCE", "OneHour");

        assertNotNull(url);
        assertTrue(url.contains("RELIANCE"));
        assertTrue(url.contains("OneHour"));
        assertTrue(url.contains("localhost:5173"));
    }

    @Test
    void testGenerateChartUrl_MultipleTimeframes() {
        when(serviceTokenHolder.asQueryParam()).thenReturn("?token=xyz789");

        String url = chartService.generateChartUrl("TCS", "FifteenMinute,OneHour,Day");

        assertNotNull(url);
        assertTrue(url.contains("TCS"));
    }

    @Test
    void testGenerateChartUrl_NullTimeframe() {
        when(serviceTokenHolder.asQueryParam()).thenReturn("?token=test");

        String url = chartService.generateChartUrl("MARUTI", "1h");

        assertNotNull(url);
        assertTrue(url.contains("MARUTI"));
    }

    @Test
    void testGetChartsTempDirectory() {
        String tempDir = chartService.getChartsTempDirectory();

        assertNotNull(tempDir);
        assertEquals("/tmp/test-charts/temp", tempDir);
    }

    private String invokePrivateMethod(String methodName, List<Double> values) {
        try {
            var method = TradingViewChartService.class.getDeclaredMethod(methodName, List.class);
            method.setAccessible(true);
            return (String) method.invoke(chartService, values);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke " + methodName, e);
        }
    }
}
