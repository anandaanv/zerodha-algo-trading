package com.dtech.algo.chart;

import com.dtech.algo.chart.config.ChartConfig;
import com.dtech.algo.chart.config.ChartType;
import com.dtech.algo.series.ExtendedBarSeries;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.series.Interval;
import com.dtech.algo.series.SeriesType;
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
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for MatplotChartCreator that verifies actual image files are created.
 * This test demonstrates the creation of charts using the Matplotlib Python bridge.
 * 
 * Key aspects of this test:
 * 1. Uses real implementation of MatplotChartCreator (no mocks)
 * 2. Creates actual chart images and saves them to disk
 * 3. Verifies the image files exist and have content
 * 
 * Note: This test requires the test_data directory with processed_ohlc_data.csv file.
 * The test creates a charts directory for the generated images.
 */
public class MatplotChartCreatorIntegrationTest {

    private MatplotChartCreator matplotChartCreator;
    private BarSeries testSeries;
    private IntervalBarSeries intervalBarSeries;
    private final String TEST_OUTPUT_DIR = "charts";
    private final String TEST_CHART_FILENAME = "matplot_test_chart.png";

    @BeforeEach
    public void setUp() throws Exception {
        // Create the test output directory if it doesn't exist
        File outputDir = new File(TEST_OUTPUT_DIR);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        // Initialize MatplotChartCreator
        matplotChartCreator = new MatplotChartCreator();
        matplotChartCreator.initialize(); // Manually call initialize
        
        // Create test data
        testSeries = createBarSeriesFromCSV("test_data/processed_ohlc_data.csv");

        // Convert to IntervalBarSeries
        intervalBarSeries = new ExtendedBarSeries(testSeries, Interval.Day, SeriesType.EQUITY, "TEST");
    }

    @AfterEach
    public void tearDown() {
        // Clean up MatplotChartCreator resources
        if (matplotChartCreator != null) {
            matplotChartCreator.cleanup();
        }
        
        // Clean up test files - commented out to allow manual inspection
        // File testFile = new File(TEST_OUTPUT_DIR + "/" + TEST_CHART_FILENAME);
        // if (testFile.exists()) {
        //     testFile.delete();
        // }
    }

    @Test
    public void testCreateMatplotChart() throws Exception {
        // Step 1: Create technical indicators
        ClosePriceIndicator closePrice = new ClosePriceIndicator(intervalBarSeries);
        SMAIndicator sma20 = new SMAIndicator(closePrice, 20);
        SMAIndicator sma50 = new SMAIndicator(closePrice, 50);

        // Create Bollinger Bands
        StandardDeviationIndicator stdDev = new StandardDeviationIndicator(closePrice, 20);
        BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(sma20);
        BollingerBandsUpperIndicator bbUpper = new BollingerBandsUpperIndicator(bbMiddle, stdDev, intervalBarSeries.numOf(2));
        BollingerBandsLowerIndicator bbLower = new BollingerBandsLowerIndicator(bbMiddle, stdDev, intervalBarSeries.numOf(2));

        // Step 2: Configure the chart
        List<CachedIndicator<?>> indicators = new ArrayList<>();
        indicators.add(sma20);
        indicators.add(sma50);
        indicators.add(bbMiddle);
        indicators.add(bbUpper);
        indicators.add(bbLower);
        
        List<IntervalBarSeries> barSeriesList = new ArrayList<>();
        barSeriesList.add(intervalBarSeries);
        
        ChartConfig config = ChartConfig.builder()
                .chartType(ChartType.CANDLESTICK)
                .title("Matplotlib Test Chart with Indicators")
                .barSeries(barSeriesList)
                .showLegend(true)
                .showVolume(true)
                .indicators(indicators)
                .build();

        // Step 3: Create the chart using MatplotChartCreator
        byte[] chartImageData = matplotChartCreator.createChart(config);
        
        // Step 4: Verify chart data was created successfully
        assertNotNull(chartImageData, "Chart image data should not be null");
        assertTrue(chartImageData.length > 0, "Chart image data should not be empty");
        
        // Step 5: Save the chart to a file for visual inspection
        File chartFile = new File(TEST_OUTPUT_DIR, TEST_CHART_FILENAME);
        try (FileOutputStream fos = new FileOutputStream(chartFile)) {
            fos.write(chartImageData);
        }
        
        // Step 6: Verify the image file exists and has content
        assertTrue(chartFile.exists(), "Chart file should exist");
        assertTrue(chartFile.length() > 0, "Chart file should not be empty");
        
        // Output the file location for manual inspection
        System.out.println("Matplotlib chart saved to: " + chartFile.getAbsolutePath());
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
                            BigDecimal.valueOf(volume)
                    );
                    bars.add(bar);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        
        return new BaseBarSeries("Test Series", bars);
    }
}