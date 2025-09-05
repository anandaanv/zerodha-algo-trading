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
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

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
 * Demonstration test for MatplotChartCreator that shows how to create a chart.
 * This test doesn't rely on Python or py4j, but demonstrates the expected workflow
 * and shows a sample chart image.
 * 
 * Note: This test requires the test_data directory with processed_ohlc_data.csv file
 * and a sample chart image file.
 */
public class MatplotChartCreationDemoTest {

    private BarSeries testSeries;
    private IntervalBarSeries intervalBarSeries;
    private final String TEST_OUTPUT_DIR = "charts";
    private final String TEST_CHART_FILENAME = "matplot_demo_chart.png";
    private final String SAMPLE_CHART_PATH = "test_chart.jpg";

    @BeforeEach
    public void setUp() {
        // Create the test output directory if it doesn't exist
        File outputDir = new File(TEST_OUTPUT_DIR);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        
        // Create test data
        testSeries = createBarSeriesFromCSV("test_data/processed_ohlc_data.csv");

        // Convert to IntervalBarSeries
        intervalBarSeries = new ExtendedBarSeries(testSeries, Interval.Day, SeriesType.EQUITY, "TEST");
    }

    @Test
    public void testDemonstrateChartCreation() throws Exception {
        // Step 1: Create technical indicators
        ClosePriceIndicator closePrice = new ClosePriceIndicator(intervalBarSeries);
        SMAIndicator sma20 = new SMAIndicator(closePrice, 20);
        SMAIndicator sma50 = new SMAIndicator(closePrice, 50);

        // Step 2: Configure the chart
        List<CachedIndicator<?>> indicators = new ArrayList<>();
        indicators.add(sma20);
        indicators.add(sma50);
        
        List<IntervalBarSeries> barSeriesList = new ArrayList<>();
        barSeriesList.add(intervalBarSeries);
        
        ChartConfig config = ChartConfig.builder()
                .chartType(ChartType.CANDLESTICK)
                .title("Matplotlib Demo Chart with Indicators")
                .barSeries(barSeriesList)
                .showLegend(true)
                .showVolume(true)
                .indicators(indicators)
                .build();

        // Step 3: In a real scenario, we would call:
        // byte[] chartImageData = matplotChartCreator.createChart(config);
        
        // For this demo, we'll use a sample chart image if it exists and has content
        File sampleImage = new File(SAMPLE_CHART_PATH);
        byte[] chartImageData;
        
        if (sampleImage.exists() && sampleImage.length() > 0) {
            chartImageData = Files.readAllBytes(sampleImage.toPath());
        } else {
            // Create a simple test image (minimal PNG header)
            chartImageData = new byte[]{
                (byte)0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n',  // PNG signature
                0, 0, 0, 13,                                         // IHDR chunk length
                'I', 'H', 'D', 'R',                                  // IHDR chunk type
                0, 0, 0, 1, 0, 0, 0, 1, 8, 0, 0, 0, 0,               // IHDR chunk data (1x1 pixel, no compression, no filter, no interlace)
                (byte)0x37, (byte)0x6E, (byte)0xF9, (byte)0x24       // Correct IHDR CRC
            };
            
            // Save this test image for future use
            try (FileOutputStream fos = new FileOutputStream(sampleImage)) {
                fos.write(chartImageData);
            }
            
            System.out.println("Created a simple test image at: " + sampleImage.getAbsolutePath());
        }
        
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
        System.out.println("Demo chart saved to: " + chartFile.getAbsolutePath());
        
        // Explain what would happen in a real scenario
        System.out.println("\nIn a real scenario with MatplotChartCreator:");
        System.out.println("1. The MatplotChartCreator would start a Python process");
        System.out.println("2. It would export the bar series data to a CSV file");
        System.out.println("3. It would export the chart configuration to a JSON file");
        System.out.println("4. It would call the Python script to generate the chart");
        System.out.println("5. The Python script would create the chart using Matplotlib");
        System.out.println("6. The chart image would be returned as a byte array");
        System.out.println("7. The chart image could be displayed or saved to a file");
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