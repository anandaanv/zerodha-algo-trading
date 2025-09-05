package com.dtech.algo.chart.test;

import com.dtech.algo.chart.MatplotChartCreator;
import com.dtech.algo.chart.config.ChartConfig;
import com.dtech.algo.chart.config.ChartType;
import com.dtech.algo.series.ExtendedBarSeries;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.series.Interval;
import com.dtech.algo.series.SeriesType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
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
 * Test for MatplotChartCreator using direct Python execution mode.
 * This test verifies that charts can be created without using the Py4J gateway.
 */
public class MatplotDirectExecutionTest {

    private MatplotChartCreator matplotChartCreator;
    private BarSeries testSeries;
    private IntervalBarSeries intervalBarSeries;
    private final String TEST_OUTPUT_DIR = "charts";
    private final String TEST_CHART_FILENAME = "matplot_direct_test_chart.png";

    @BeforeEach
    public void setUp() throws Exception {
        // Create the test output directory if it doesn't exist
        File outputDir = new File(TEST_OUTPUT_DIR);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        // Initialize MatplotChartCreator
        matplotChartCreator = new MatplotChartCreator();
        
        // Configure for direct execution
        ReflectionTestUtils.setField(matplotChartCreator, "useDirectExecution", true);
        
        // Set Python executable path - adjust this to match your environment
        ReflectionTestUtils.setField(matplotChartCreator, "pythonExecutable", "/usr/bin/python3");
        
        // Initialize the chart creator
        matplotChartCreator.initialize();
        
        // Create test data from CSV
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
    }

    @Test
    public void testDirectPythonExecution() throws Exception {
        // Create a simple chart configuration
        List<IntervalBarSeries> barSeriesList = new ArrayList<>();
        barSeriesList.add(intervalBarSeries);
        
        ChartConfig config = ChartConfig.builder()
                .chartType(ChartType.CANDLESTICK)
                .title("Direct Python Execution Test Chart")
                .barSeries(barSeriesList)
                .showLegend(true)
                .showVolume(true)
                .build();

        // Create the chart
        byte[] chartImageData = matplotChartCreator.createChart(config);
        
        // Verify chart data was created successfully
        assertNotNull(chartImageData, "Chart image data should not be null");
        assertTrue(chartImageData.length > 0, "Chart image data should not be empty");
        
        // Save the chart to a file for visual inspection
        File chartFile = new File(TEST_OUTPUT_DIR, TEST_CHART_FILENAME);
        try (FileOutputStream fos = new FileOutputStream(chartFile)) {
            fos.write(chartImageData);
        }
        
        // Verify the image file exists and has content
        assertTrue(chartFile.exists(), "Chart file should exist");
        assertTrue(chartFile.length() > 0, "Chart file should not be empty");
        
        // Output the file location for manual inspection
        System.out.println("Direct execution chart saved to: " + chartFile.getAbsolutePath());
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
            // Skip header if present
            if (br.ready()) {
                String firstLine = br.readLine();
                if (firstLine.contains("date") || firstLine.contains("time")) {
                    // It's a header, skip it
                } else {
                    // Not a header, process it
                    processLine(firstLine, formatter, bars);
                }
            }
            
            while ((line = br.readLine()) != null) {
                processLine(line, formatter, bars);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        
        return new BaseBarSeries("Test Series", bars);
    }
    
    private void processLine(String line, DateTimeFormatter formatter, List<Bar> bars) {
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
}