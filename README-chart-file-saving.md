# Chart File Saving Functionality

This document describes how to use the chart file saving functionality that has been added to the chart generation components.

## Overview

The chart generation system now supports saving generated charts directly to disk files in addition to returning them as byte arrays. This feature is available in both the `MatplotChartCreator` and `ChartGrid` classes.

## Using MatplotChartCreator to Save Charts

The `MatplotChartCreator` class now has an overloaded `createChart` method that accepts a file path parameter:

```java
// Generate a chart and save it to a file
byte[] chartImageData = matplotChartCreator.createChart(config, "/path/to/save/chart.png");
```

The method will:
1. Generate the chart as before
2. Save the chart to the specified file path
3. Return the chart image data as a byte array

If the file path is `null` or empty, the chart will not be saved to disk.

## Using ChartGrid to Save Charts

Similarly, the `ChartGrid` class now has an overloaded `createChartGrid` method that accepts a file path parameter:

```java
// Generate a chart grid and save it to a file
byte[] gridImageData = chartGrid.createChartGrid(seriesList, gridConfig, "/path/to/save/grid.png");
```

The method will:
1. Generate the chart grid as before
2. Save the grid to the specified file path
3. Return the grid image data as a byte array

If the file path is `null` or empty, the chart grid will not be saved to disk.

## Testing the Functionality

A new endpoint has been added to the `ChartController` to test the file saving functionality:

```
GET /api/charts/test/save?filename=my_chart.png
```

This endpoint:
1. Generates a test chart with sample data
2. Saves it to the specified filename (or a default filename if not provided)
3. Returns the chart image as a response

The default filename format is `test_chart_yyyy-MM-dd_HH-mm-ss.png` if no filename is provided.

## Example Usage

```java
// Example 1: Generate a chart and save it to a file
ChartConfig config = ChartConfig.builder()
        .barSeries(barSeries)
        .chartType(ChartType.CANDLESTICK)
        .showVolume(true)
        .showLegend(true)
        .title("NIFTY - Daily Chart")
        .build();

// Save to a file and get the image data
byte[] chartImageData = matplotChartCreator.createChart(config, "nifty_daily.png");

// Example 2: Generate a chart grid and save it to a file
List<IntervalBarSeries> seriesList = new ArrayList<>();
seriesList.add(niftyDailySeries);
seriesList.add(niftyHourlySeries);
seriesList.add(nifty15MinSeries);
seriesList.add(nifty5MinSeries);

ChartGrid.GridConfig gridConfig = ChartGrid.GridConfig.defaultConfig();

// Save to a file and get the image data
byte[] gridImageData = chartGrid.createChartGrid(seriesList, gridConfig, "nifty_multi_timeframe.png");
```

## Notes

- The file saving functionality will create any necessary parent directories if they don't exist
- If there's an error saving the file, it will be logged but won't prevent the chart from being generated and returned
- Supported file formats are PNG and JPG, determined by the file extension