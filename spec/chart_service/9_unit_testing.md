# Unit Testing Specification for Chart Service

## Overview

This document outlines the unit testing strategy for the chart service components, with a focus on ensuring that generated charts are interpretable by both humans and AI systems. The tests will verify chart creation, rendering, export functionality, and proper handling of technical indicators.

## Test Data Source

All chart service tests will use a consistent data source approach based on the existing `OHLCAnalyzerTest` implementation. This ensures tests are reproducible and based on realistic market data.

### Data Loading Implementation

```java
private BarSeries createBarSeriesFromCSV(String csvFile) {
    BarSeries series = new BaseBarSeries();
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    try (BufferedReader br = new BufferedReader(new FileReader(csvFile))) {
        String line;
        while ((line = br.readLine()) != null) {
            String[] values = line.split(",");
            LocalDateTime dateTime = LocalDateTime.parse(values[0], formatter);
            double open = Double.parseDouble(values[1]);
            double high = Double.parseDouble(values[2]);
            double low = Double.parseDouble(values[3]);
            double close = Double.parseDouble(values[4]);
            Bar bar = new BaseBar(Duration.ofDays(1), dateTime.atZone(ZoneOffset.UTC), BigDecimal.valueOf(open),
                    BigDecimal.valueOf(high), BigDecimal.valueOf(low), BigDecimal.valueOf(close), BigDecimal.valueOf(0));
            series.addBar(bar);
        }
    } catch (IOException e) {
        e.printStackTrace();
    }
    return series;
}
```

### Test Data File

Tests will use the same CSV file located at `test_data/processed_ohlc_data.csv` for consistency across all chart-related tests.

## Test Classes

### 1. ComplexChartCreatorTest

```java
package com.dtech.algo.chart;

import com.dtech.algo.chart.config.ChartConfig;
import com.dtech.algo.chart.config.ChartType;
import com.dtech.algo.chart.renderer.ChartRendererFactory;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.series.Interval;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.XYPlot;
import org.jfree.data.xy.OHLCDataset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;

import java.awt.Rectangle;
import java.io.BufferedReader;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public class ComplexChartCreatorTest {

    @Mock
    private ChartRendererFactory rendererFactory;

    @Mock
    private IndicatorCategoryService indicatorCategoryService;

    private ComplexChartCreator chartCreator;
    private BarSeries testSeries;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        chartCreator = new ComplexChartCreator(rendererFactory, indicatorCategoryService);
        testSeries = createBarSeriesFromCSV("test_data/processed_ohlc_data.csv");
    }

    @Test
    public void testCreateChart() {
        // Test implementation
    }

    @Test
    public void testExportChartAsSVG() {
        // Test implementation
    }

    @Test
    public void testExportChartAsPNG() {
        // Test implementation
    }

    @Test
    public void testAddIndicatorsToChart() {
        // Test implementation
    }

    private BarSeries createBarSeriesFromCSV(String csvFile) {
        // Implementation from OHLCAnalyzerTest
    }
}
```

#### Key Test Cases

1. **Basic Chart Creation**
   - Verify chart is created with correct type
   - Verify axes are properly configured
   - Verify title and labels are set

2. **SVG Export**
   - Verify SVG is generated with valid structure
   - Verify metadata is included in SVG
   - Verify chart elements are present in SVG

3. **PNG Export**
   - Verify PNG is generated with correct dimensions
   - Verify image is not empty or corrupted

4. **Indicator Addition**
   - Test adding overlay indicators (moving averages)
   - Test adding separate panel indicators (RSI, MACD)
   - Verify indicator appearance and placement

5. **Error Handling**
   - Test with invalid configuration
   - Test with empty data series
   - Verify appropriate error handling

### 2. ChartGridTest

```java
package com.dtech.algo.chart;

import com.dtech.algo.chart.config.ChartConfig;
import org.jfree.chart.JFreeChart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ChartGridTest {

    @Mock
    private MetadataService metadataService;

    private ChartGrid chartGrid;
    private List<JFreeChart> testCharts;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        chartGrid = new ChartGrid(metadataService);
        testCharts = createTestCharts();
    }

    @Test
    public void testArrangeCharts() {
        // Test implementation
    }

    @Test
    public void testExportChartsAsSVG() {
        // Test implementation
    }

    @Test
    public void testExportChartsAsPNG() {
        // Test implementation
    }

    @Test
    public void testCalculateRows() {
        // Test implementation
    }

    @Test
    public void testCalculateColumns() {
        // Test implementation
    }

    private List<JFreeChart> createTestCharts() {
        // Create test charts
        return new ArrayList<>();
    }
}
```

#### Key Test Cases

1. **Grid Layout Calculation**
   - Test with 1, 2, 3, 4, 5, and 6 charts
   - Verify row and column calculations are correct
   - Test edge cases (empty list, null input)

2. **Chart Arrangement**
   - Verify charts are properly positioned in grid
   - Verify overall dimensions are appropriate
   - Test with different chart types in same grid

3. **SVG Export**
   - Verify grid structure in SVG
   - Verify relationship metadata between charts
   - Validate SVG structure and content

4. **PNG Export**
   - Verify grid layout in PNG
   - Test with various dimensions

### 3. Indicator Visualization Tests

#### MACDVisualizerTest

```java
package com.dtech.algo.chart.indicator;

import com.dtech.algo.chart.indicator.MACDVisualizer;
import org.jfree.chart.plot.XYPlot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.MACDIndicator;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class MACDVisualizerTest {

    private MACDVisualizer macdVisualizer;
    private BarSeries testSeries;

    @BeforeEach
    public void setUp() {
        macdVisualizer = new MACDVisualizer();
        testSeries = createBarSeriesFromCSV("test_data/processed_ohlc_data.csv");
    }

    @Test
    public void testCreateIndicatorPlot() {
        // Test implementation
    }

    @Test
    public void testGenerateIndicatorMetadata() {
        // Test implementation
    }

    private BarSeries createBarSeriesFromCSV(String csvFile) {
        // Implementation from OHLCAnalyzerTest
    }
}
```

#### RSIVisualizerTest

```java
package com.dtech.algo.chart.indicator;

import org.jfree.chart.plot.XYPlot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.RSIIndicator;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class RSIVisualizerTest {

    private RSIVisualizer rsiVisualizer;
    private BarSeries testSeries;

    @BeforeEach
    public void setUp() {
        rsiVisualizer = new RSIVisualizer();
        testSeries = createBarSeriesFromCSV("test_data/processed_ohlc_data.csv");
    }

    @Test
    public void testCreateIndicatorPlot() {
        // Test implementation
    }

    @Test
    public void testGenerateIndicatorMetadata() {
        // Test implementation
    }

    private BarSeries createBarSeriesFromCSV(String csvFile) {
        // Implementation from OHLCAnalyzerTest
    }
}
```

### 4. SVG Export and Optimization Tests

```java
package com.dtech.algo.chart.export;

import com.dtech.algo.chart.config.ChartConfig;
import org.jfree.chart.JFreeChart;
import org.jfree.graphics2d.svg.SVGGraphics2D;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;

import java.awt.Rectangle;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class SVGExporterTest {

    private SVGExporter svgExporter;
    private BarSeries testSeries;
    private JFreeChart testChart;

    @BeforeEach
    public void setUp() {
        svgExporter = new SVGExporter();
        testSeries = createBarSeriesFromCSV("test_data/processed_ohlc_data.csv");
        // Create test chart
    }

    @Test
    public void testExportChartAsSVG() {
        // Test implementation
    }

    @Test
    public void testEnhanceSVGStructure() {
        // Test implementation
    }

    @Test
    public void testAddChartMetadata() {
        // Test implementation
    }

    @Test
    public void testSVGParseability() {
        // Test SVG can be parsed by XML parsers
    }

    @Test
    public void testMetadataExtraction() {
        // Test metadata can be extracted from SVG
    }

    private BarSeries createBarSeriesFromCSV(String csvFile) {
        // Implementation from OHLCAnalyzerTest
    }
}
```

## Integration Test

```java
package com.dtech.algo.chart;

import com.dtech.algo.chart.config.ChartConfig;
import com.dtech.algo.chart.config.ChartType;
import com.dtech.algo.chart.indicator.IndicatorConfig;
import org.jfree.chart.JFreeChart;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.ta4j.core.BarSeries;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class ChartServiceIntegrationTest {

    @Autowired
    private ComplexChartCreator chartCreator;

    @Autowired
    private ChartGrid chartGrid;

    @Test
    public void testEndToEndChartCreationAndExport() {
        // 1. Load test data
        BarSeries testSeries = createBarSeriesFromCSV("test_data/processed_ohlc_data.csv");

        // 2. Create chart config with indicators
        ChartConfig config = ChartConfig.builder()
            .chartType(ChartType.CANDLESTICK)
            .title("AAPL Daily")
            .barSeries(testSeries)
            .indicators(Arrays.asList(
                new IndicatorConfig("EMA", 20),
                new IndicatorConfig("RSI", 14),
                new IndicatorConfig("MACD", 12, 26, 9)
            ))
            .build();

        // 3. Create chart
        JFreeChart chart = chartCreator.createChart(config);
        assertNotNull(chart);

        // 4. Export as SVG
        String svg = chartCreator.exportChartAsSVG(chart, config);
        assertNotNull(svg);
        assertTrue(svg.contains("<svg"));
        assertTrue(svg.contains("</svg>"));

        // 5. Create chart grid
        List<JFreeChart> charts = Arrays.asList(chart, chart);
        List<ChartConfig> configs = Arrays.asList(config, config);
        JFreeChart gridChart = chartGrid.arrangeCharts(charts, "Multi-Chart View");
        assertNotNull(gridChart);

        // 6. Export grid as SVG
        String gridSvg = chartGrid.exportChartsAsSVG(charts, "Multi-Chart View", configs);
        assertNotNull(gridSvg);
        assertTrue(gridSvg.contains("<svg"));
        assertTrue(gridSvg.contains("</svg>"));
    }

    private BarSeries createBarSeriesFromCSV(String csvFile) {
        // Implementation from OHLCAnalyzerTest
    }
}
```

## AI Interpretability Test

To validate that charts are truly interpretable by AI models, we'll create a specialized test that uses pre-defined metrics to evaluate AI-readability of SVG output.

```java
package com.dtech.algo.chart;

import com.dtech.algo.chart.config.ChartConfig;
import com.dtech.algo.chart.config.ChartType;
import org.jfree.chart.JFreeChart;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.ta4j.core.BarSeries;
org.w3c.dom.Document;
org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class AIInterpretabilityTest {

    @Autowired
    private ComplexChartCreator chartCreator;

    @Test
    public void testSVGAIInterpretability() throws Exception {
        // 1. Load test data
        BarSeries testSeries = createBarSeriesFromCSV("test_data/processed_ohlc_data.csv");

        // 2. Create chart config
        ChartConfig config = ChartConfig.builder()
            .chartType(ChartType.CANDLESTICK)
            .title("AAPL Daily")
            .barSeries(testSeries)
            .build();

        // 3. Create and export chart as SVG
        JFreeChart chart = chartCreator.createChart(config);
        String svg = chartCreator.exportChartAsSVG(chart, config);

        // 4. Parse SVG as XML document
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));

        // 5. Verify SVG contains required AI-interpretability features

        // 5.1 Check for metadata
        assertNotNull(findMetadataElement(doc), "SVG should contain metadata element");

        // 5.2 Check for semantic grouping
        assertTrue(countElementsByClass(doc, "candlestick") > 0, "SVG should group candlestick elements");

        // 5.3 Check for data attributes
        assertTrue(hasDataAttributes(doc), "SVG elements should have data-* attributes");

        // 5.4 Check for chart context information
        assertTrue(svg.contains("instrument"), "SVG should contain instrument information");
        assertTrue(svg.contains("timeframe"), "SVG should contain timeframe information");
    }

    private Node findMetadataElement(Document doc) {
        // Implementation to find metadata element
        return null;
    }

    private int countElementsByClass(Document doc, String className) {
        // Implementation to count elements by class
        return 0;
    }

    private boolean hasDataAttributes(Document doc) {
        // Implementation to check for data-* attributes
        return false;
    }

    private BarSeries createBarSeriesFromCSV(String csvFile) {
        // Implementation from OHLCAnalyzerTest
    }
}
```

## Performance Tests

To ensure chart generation is efficient, especially for high-frequency trading applications:

```java
package com.dtech.algo.chart;

import com.dtech.algo.chart.config.ChartConfig;
import com.dtech.algo.chart.config.ChartType;
import org.jfree.chart.JFreeChart;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.ta4j.core.BarSeries;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class ChartPerformanceTest {

    @Autowired
    private ComplexChartCreator chartCreator;

    @Test
    public void testChartCreationPerformance() {
        // 1. Load test data
        BarSeries testSeries = createBarSeriesFromCSV("test_data/processed_ohlc_data.csv");

        // 2. Create chart config
        ChartConfig config = ChartConfig.builder()
            .chartType(ChartType.CANDLESTICK)
            .title("Performance Test")
            .barSeries(testSeries)
            .build();

        // 3. Measure creation time for multiple iterations
        long startTime = System.currentTimeMillis();
        int iterations = 100;

        for (int i = 0; i < iterations; i++) {
            JFreeChart chart = chartCreator.createChart(config);
            assertNotNull(chart);
        }

        long endTime = System.currentTimeMillis();
        long averageTime = (endTime - startTime) / iterations;

        // 4. Assert performance is within acceptable range
        assertTrue(averageTime < 100, "Chart creation should take less than 100ms on average");

        System.out.println("Average chart creation time: " + averageTime + "ms");
    }

    @Test
    public void testSVGExportPerformance() {
        // Similar implementation for SVG export performance
    }

    private BarSeries createBarSeriesFromCSV(String csvFile) {
        // Implementation from OHLCAnalyzerTest
    }
}
```

## Test Setup Requirements

1. **Test Data**
   - Ensure `test_data/processed_ohlc_data.csv` is available in the test resources
   - CSV format should match the expected format in the data loading method

2. **Dependencies**
   - JUnit 5 for test framework
   - Mockito for mocking dependencies
   - Spring Boot Test for integration testing
   - XML parsing libraries for SVG validation

3. **Test Environment**
   - Tests should run in both CI/CD pipeline and local development
   - Performance tests may be separated into a different suite

## Code Coverage Goals

1. **Unit Test Coverage**
   - Aim for >90% line coverage for all chart service classes
   - 100% coverage for critical components (SVG export, indicator visualization)

2. **Test Categories**
   - Functional correctness (charts display correctly)
   - AI interpretability (SVG structure and metadata)
   - Performance (chart generation speed)
   - Error handling (invalid inputs, edge cases)

## Conclusion

This comprehensive testing strategy ensures that the chart service components function correctly, generate AI-interpretable visualizations, and maintain performance standards. By using consistent test data and thorough validation of the SVG output, we can ensure that charts will be properly interpreted by both humans and AI systems like OpenAI's GPT models.
