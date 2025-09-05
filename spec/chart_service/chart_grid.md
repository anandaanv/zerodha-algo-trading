# ChartGrid Service

## Overview

The ChartGrid service provides functionality to create a grid of charts from multiple IntervalBarSeries and combine them into a single image. This is useful for comparing multiple instruments or timeframes in a single view.

## Features

- Create a grid of charts with configurable layout (columns, dimensions, padding)
- Support for both MatplotChartCreator and ComplexChartCreator
- Configurable chart options for all charts in the grid
- Automatic handling of grid dimensions based on the number of series

## Usage

### Basic Usage

```java
// Create a list of IntervalBarSeries
List<IntervalBarSeries> seriesList = new ArrayList<>();
seriesList.add(barSeriesHelper.getIntervalBarSeries("RELIANCE", "DAY"));
seriesList.add(barSeriesHelper.getIntervalBarSeries("INFY", "DAY"));
seriesList.add(barSeriesHelper.getIntervalBarSeries("TCS", "DAY"));
seriesList.add(barSeriesHelper.getIntervalBarSeries("HDFCBANK", "DAY"));

// Create grid config
ChartGrid.GridConfig gridConfig = ChartGrid.GridConfig.builder()
        .columns(2)
        .width(800)
        .height(600)
        .padding(10)
        .useMatplotlib(true)
        .baseConfig(createBaseConfig())
        .build();

// Create chart grid
byte[] chartImageBytes = chartGrid.createChartGrid(seriesList, gridConfig);

// Save to file or return as response
Files.write(Paths.get("chart_grid.jpg"), chartImageBytes);
```

### Configuration Options

The `GridConfig` class provides the following configuration options:

- `columns`: Number of columns in the grid (default: 2)
- `width`: Width of each chart in pixels (default: 800)
- `height`: Height of each chart in pixels (default: 600)
- `padding`: Padding between charts in pixels (default: 10)
- `useMatplotlib`: Whether to use MatplotChartCreator or ComplexChartCreator (default: true)
- `baseConfig`: Base configuration for all charts (default: new ChartConfig())

### Creating a Base Configuration

```java
private ChartConfig createBaseConfig() {
    ChartConfig config = new ChartConfig();
    config.setChartType(ChartType.CANDLESTICK);
    config.setShowVolume(true);
    config.setShowLegend(true);
    
    // Add indicators
    List<CachedIndicator<?>> indicators = new ArrayList<>();
    
    // Add SMA
    ClosePriceIndicator closePrice = new ClosePriceIndicator(barSeries);
    SMAIndicator sma20 = new SMAIndicator(closePrice, 20);
    indicators.add(sma20);
    
    // Add RSI
    RSIIndicator rsi = new RSIIndicator(closePrice, 14);
    indicators.add(rsi);
    
    config.setIndicators(indicators);
    
    return config;
}
```

## Implementation Details

The ChartGrid class uses the following approach to create a grid of charts:

1. For each IntervalBarSeries, create a chart configuration based on the base config
2. Generate chart images using either MatplotChartCreator or ComplexChartCreator
3. Calculate the grid dimensions based on the number of series and columns
4. Create a new BufferedImage to hold the grid
5. Place each chart image in the grid at the appropriate position
6. Convert the combined image to a byte array

## Error Handling

If a chart fails to generate, a placeholder image is created with an error message. This ensures that the grid is still created even if some charts fail.

## Example Output

A 2x2 grid of charts might look like:

```
+----------------+----------------+
|                |                |
|   Chart 1      |   Chart 2      |
|                |                |
+----------------+----------------+
|                |                |
|   Chart 3      |   Chart 4      |
|                |                |
+----------------+----------------+
```

## Dependencies

- MatplotChartCreator or ComplexChartCreator for generating individual charts
- Java AWT for image manipulation
- ImageIO for image conversion