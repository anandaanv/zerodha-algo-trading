package com.dtech.algo.service;

import com.dtech.algo.controller.dto.TradingViewChartRequest;
import com.dtech.algo.controller.dto.TradingViewChartResponse;
import com.dtech.algo.series.ExtendedBarSeries;
import com.dtech.algo.series.Interval;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.kitecon.controller.BarSeriesHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradingViewChartServiceTest {

    @Mock
    private BarSeriesHelper barSeriesHelper;

    @InjectMocks
    private TradingViewChartService tradingViewChartService;

    @BeforeEach
    void setUp() {
        // Set up test directories
        ReflectionTestUtils.setField(tradingViewChartService, "chartsOutputDirectory", "./target/test-charts");
        ReflectionTestUtils.setField(tradingViewChartService, "chartsTempDirectory", "./target/test-charts/temp");
    }

    @Test
    void generateTradingViewCharts_NoData_ReturnsErrorResponse() {
        // Arrange
        TradingViewChartRequest request = TradingViewChartRequest.builder()
                .symbol("TESTSTOCK")
                .timeframes(List.of(Interval.OneHour, Interval.Day))
                .build();

        when(barSeriesHelper.getIntervalBarSeries(anyString(), anyString()))
                .thenReturn(null);

        // Act
        TradingViewChartResponse response = tradingViewChartService.generateTradingViewCharts(request);

        // Assert
        assertNotNull(response);
        assertEquals("TESTSTOCK", response.getSymbol());
        assertNotNull(response.getErrorMessage());
        assertTrue(response.getErrorMessage().contains("No data available"));
    }

    @Test
    void generateChartDataJsons_ValidSeries_ReturnsCorrectJson() throws Exception {
        // Create a mock IntervalBarSeries
        IntervalBarSeries mockSeries = createMockBarSeries(Interval.OneHour, 5);

        // Use reflection to access the private method
        List<String> jsonResults = (List<String>) ReflectionTestUtils.invokeMethod(
                tradingViewChartService, 
                "generateChartDataJsons", 
                List.of(mockSeries));

        // Assert
        assertNotNull(jsonResults);
        assertEquals(1, jsonResults.size());
        assertTrue(jsonResults.get(0).startsWith("["));
        assertTrue(jsonResults.get(0).endsWith("]"));
        assertTrue(jsonResults.get(0).contains("\"time\":"));
        assertTrue(jsonResults.get(0).contains("\"open\":"));
    }

    /**
     * Helper method to create a mock bar series with test data
     */
    private IntervalBarSeries createMockBarSeries(Interval interval, int barCount) {
        // Create a test bar series
        IntervalBarSeries series = new ExtendedBarSeries();

        ZonedDateTime time = ZonedDateTime.now().minusHours(barCount);

        for (int i = 0; i < barCount; i++) {
            // Add a bar with some test data
            double basePrice = 100.0 + i;
            series.addBar(
                time.plusHours(i),
                basePrice,
                basePrice + 2.0,
                basePrice - 1.0,
                basePrice + 0.5,
                1000.0 * (i + 1)
            );
        }

        return series;
    }
}
