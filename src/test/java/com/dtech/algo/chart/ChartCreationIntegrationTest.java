package com.dtech.algo.chart;

import com.dtech.algo.chart.config.ChartConfig;
import com.dtech.algo.chart.config.ChartType;
import com.dtech.algo.chart.indicator.IndicatorCategoryService;
import com.dtech.algo.chart.renderer.ChartRendererFactory;
import com.dtech.algo.series.ExtendedBarSeries;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.series.Interval;
import com.dtech.algo.series.SeriesType;
import org.jfree.chart.JFreeChart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for chart creation that verifies actual image files are created.
 * This is a blackbox test that doesn't use mocks and verifies the actual image creation.
 * 
 * Key aspects of this approach:
 * 1. Uses real implementations of all components (no mocks)
 * 2. Creates actual chart images and saves them to disk
 * 3. Verifies the image files exist and have content
 * 4. Cleans up after itself by deleting test files
 * 
 * Future enhancements could include:
 * - Visual verification of chart elements (requires image processing libraries)
 * - Comparison with reference/expected images
 * - Testing with different chart types and configurations
 * - Testing with different indicators and combinations
 * - Performance testing for large datasets
 * 
 * Note: This test requires the test_data directory with processed_ohlc_data.csv file.
 * The test creates a test_output directory for the generated images.
 */
public class ChartCreationIntegrationTest {

    private ChartRendererFactory rendererFactory;
    private IndicatorCategoryService indicatorCategoryService;
    private ComplexChartCreator chartCreator;
    private BarSeries testSeries;
    private IntervalBarSeries intervalBarSeries;
    private final String TEST_OUTPUT_DIR = "charts";
    private final String TEST_CHART_FILENAME = "test_chart";

    @BeforeEach
    public void setUp() {
        // Create the test output directory if it doesn't exist
        File outputDir = new File(TEST_OUTPUT_DIR);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        // Initialize the required components
        rendererFactory = new ChartRendererFactory();
        indicatorCategoryService = new IndicatorCategoryService();
        chartCreator = new ComplexChartCreator(rendererFactory, indicatorCategoryService);

        // Create test data
        testSeries = createBarSeriesFromCSV("test_data/processed_ohlc_data.csv");

        // Convert to IntervalBarSeries
        intervalBarSeries = new ExtendedBarSeries(testSeries, Interval.Day, SeriesType.EQUITY, "TEST");
    }

    @AfterEach
    public void tearDown() {
        // Clean up test files
        File testFile = new File(TEST_OUTPUT_DIR + "/" + TEST_CHART_FILENAME + ".png");
        if (testFile.exists()) {
//            testFile.delete();
        }
    }

    @Test
    public void testCreateAndSaveChart() {
        // Step 1: Create technical indicators
        // We're using a Simple Moving Average (SMA) with period 20 on the close price
        ClosePriceIndicator closePrice = new ClosePriceIndicator(intervalBarSeries);
        SMAIndicator sma20 = new SMAIndicator(closePrice, 20);

        // Create RSI indicator
        RSIIndicator rsiIndicator = new RSIIndicator(closePrice, 14);

        // Create MACD indicator
        MACDIndicator macdIndicator = new MACDIndicator(closePrice, 12, 26);

        StandardDeviationIndicator stdDev = new StandardDeviationIndicator(closePrice, 20);
        BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(sma20);
        BollingerBandsUpperIndicator bbUpper = new BollingerBandsUpperIndicator(bbMiddle, stdDev, intervalBarSeries.numOf(2));
        BollingerBandsLowerIndicator bbLower = new BollingerBandsLowerIndicator(bbMiddle, stdDev, intervalBarSeries.numOf(2));

        // Create EMA indicators
        EMAIndicator ema50 = new EMAIndicator(closePrice, 50);
        EMAIndicator ema200 = new EMAIndicator(closePrice, 200);

        // Create ADX indicator
        ADXIndicator adxIndicator = new ADXIndicator(intervalBarSeries, 14);


        // Step 2: Configure the chart with all required parameters
        // This includes chart type, title, data series, and indicators
        
        // Create a list of all compatible indicators (CachedIndicator type)
        List<CachedIndicator<?>> indicators = new ArrayList<>();
        
        // Add all indicators that are instances of CachedIndicator
        indicators.add(sma20);
        indicators.add(rsiIndicator);
        indicators.add(macdIndicator);
        indicators.add(bbMiddle);
        indicators.add(bbUpper);
        indicators.add(bbLower);
        indicators.add(ema50);
        indicators.add(ema200);
        // Note: ADX indicator is not added as it may not be a CachedIndicator
        
        ChartConfig config = ChartConfig.builder()
                .chartType(ChartType.CANDLESTICK)  // Using candlestick chart type
                .title("Test Chart with Multiple Indicators")  // Descriptive title
                .barSeries(List.of(intervalBarSeries))      // Our price data
                .showLegend(true)                  // Show the legend for better readability
                .showVolume(true)                  // Include volume information
                .indicators(indicators)            // Add all our indicators
                .build();

        // Step 3: Create the actual chart using the ComplexChartCreator
        // This is where the real chart creation happens (no mocks)
        JFreeChart chart = chartCreator.createChart(config);
        
        // Step 4: Verify chart was created successfully
        assertNotNull(chart, "Chart should not be null");
        
        // Step 5: Save the chart to a PNG file
        // This tests the actual image creation functionality
        chartCreator.saveChartAsPng(TEST_CHART_FILENAME, TEST_OUTPUT_DIR, chart);
        
        // Step 6: Verify the image file exists and has content
        File chartFile = new File(TEST_OUTPUT_DIR + "/" + TEST_CHART_FILENAME + ".png");
        assertTrue(chartFile.exists(), "Chart file should exist");
        assertTrue(chartFile.length() > 0, "Chart file should not be empty");
        
        // Output the file location for manual inspection if needed
        System.out.println("Chart saved to: " + chartFile.getAbsolutePath());
        
        // Note: For a more comprehensive test, you could:
        // - Load the image and verify its dimensions
        // - Check specific pixels or image characteristics
        // - Compare with a reference image
        // - Verify chart elements like title, axes, etc.
    }

    /**
     * Creates a bar series from a CSV file
     * @param csvFile Path to the CSV file
     * @return A BarSeries containing the data from the CSV file
     */
    private BarSeries createBarSeriesFromCSV(String csvFile) {
        List<Bar> bars = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        
        try (BufferedReader br = new BufferedReader(new FileReader(csvFile))) {
            String line;
            // Skip header
            br.readLine();
            
            while ((line = br.readLine()) != null) {
                String[] values = line.split(",");
                if (values.length >= 5) {
                    LocalDateTime dateTime = LocalDateTime.parse(values[0], formatter);
                    double open = Double.parseDouble(values[1]);
                    double high = Double.parseDouble(values[2]);
                    double low = Double.parseDouble(values[3]);
                    double close = Double.parseDouble(values[4]);
                    double volume = values.length > 5 ? Double.parseDouble(values[5]) : 0;
                    
                    Bar bar = new BaseBar(
                            Duration.ofDays(1),
                            dateTime.atZone(ZoneOffset.UTC),
                            BigDecimal.valueOf(open),
                            BigDecimal.valueOf(high),
                            BigDecimal.valueOf(low),
                            BigDecimal.valueOf(close),
                            BigDecimal.valueOf(volume));
                    
                    bars.add(bar);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read CSV file: " + csvFile, e);
        }
        
        return new BaseBarSeries("CSV Data", bars);
    }
}