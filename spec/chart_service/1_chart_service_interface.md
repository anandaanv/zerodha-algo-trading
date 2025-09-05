# Chart Service Interface Specification

## Overview

The `ChartService` interface serves as the main entry point for chart generation functionality in the trading system. This interface is located in the `com.dtech.algo.service` package and provides methods to create and save chart images.

## Interface Definition

```java
package com.dtech.algo.service;

import com.dtech.algo.chart.config.ChartConfig;
import java.util.List;

public interface ChartService {
    /**
     * Generate a multi-chart image with the provided chart configurations
     * @param chartConfigs List of chart configurations
     * @param filename Name for the output file (without extension)
     * @return Path to the generated chart image file
     */
    String generateCharts(List<ChartConfig> chartConfigs, String filename);

    /**
     * Generate a single chart with the provided configuration
     * @param chartConfig Chart configuration
     * @param filename Name for the output file (without extension)
     * @return Path to the generated chart image file
     */
    String generateChart(ChartConfig chartConfig, String filename);
}
```

## Implementation Details

The `ChartService` interface will be implemented by a `ChartServiceImpl` class in the same package. The implementation will:

1. Provide the public API implementation for chart generation
2. Validate input parameters and prepare configurations
3. Delegate the actual chart creation to `ComplexChartCreator`
4. Delegate the chart layout arrangement to `ChartGrid`
5. Handle the file saving and path management

This class focuses on coordination rather than implementation details, following the principle of separation of concerns.

```java
package com.dtech.algo.service;

import com.dtech.algo.chart.ComplexChartCreator;
import com.dtech.algo.chart.ChartGrid;
import com.dtech.algo.chart.config.ChartConfig;
import com.dtech.algo.chart.util.ChartSaveUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jfree.chart.JFreeChart;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChartServiceImpl implements ChartService {

    private final ComplexChartCreator chartCreator;
    private final ChartGrid chartGrid;
    private final ChartSaveUtil chartSaveUtil;

    @Override
    public String generateChart(ChartConfig chartConfig, String filename) {
        validateConfig(chartConfig);
        JFreeChart chart = chartCreator.createChart(chartConfig);
        return chartSaveUtil.saveChart(chart, filename);
    }

    @Override
    public String generateCharts(List<ChartConfig> chartConfigs, String filename) {
        if (chartConfigs == null || chartConfigs.isEmpty()) {
            throw new IllegalArgumentException("Chart configurations cannot be null or empty");
        }

        chartConfigs.forEach(this::validateConfig);

        // If only one chart, use the single chart method
        if (chartConfigs.size() == 1) {
            return generateChart(chartConfigs.get(0), filename);
        }

        // Create individual charts
        List<JFreeChart> charts = new ArrayList<>();
        for (ChartConfig config : chartConfigs) {
            charts.add(chartCreator.createChart(config));
        }

        // Arrange charts in a grid
        JFreeChart combinedChart = chartGrid.arrangeCharts(charts, filename);

        // Save the combined chart
        return chartSaveUtil.saveChart(combinedChart, filename);
    }

    private void validateConfig(ChartConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Chart configuration cannot be null");
        }
        if (config.getBarSeries() == null) {
            throw new IllegalArgumentException("Bar series cannot be null");
        }
        if (config.getChartType() == null) {
            throw new IllegalArgumentException("Chart type cannot be null");
        }
    }
}
```

## File Management

The service will handle file management through the `ChartSaveUtil` class:

- Charts will be saved to a 'charts' directory in the project
- Filenames will include a timestamp to avoid overwrites
- Both PNG and JPEG formats will be supported

## Thread Safety

The `ChartService` implementation will be thread-safe, allowing multiple charts to be generated concurrently from different threads. It will use immutable configuration objects and thread-safe utility classes.
