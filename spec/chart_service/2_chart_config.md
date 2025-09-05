# Chart Configuration Specification

## Overview

The `ChartConfig` class and related components in the `com.dtech.algo.chart.config` package define the configuration options for chart generation in the trading system. These classes allow for flexible and comprehensive chart customization.

## ChartConfig Class

A data class that encapsulates all configuration options for a single chart.

```java
package com.dtech.algo.chart.config;

import com.dtech.algo.series.IntervalBarSeries;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import org.ta4j.core.indicators.CachedIndicator;

import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
public class ChartConfig {
    private IntervalBarSeries barSeries;          // Price data
    private List<CachedIndicator<?>> indicators;  // Technical indicators to display
    private ChartType chartType;                  // Candle, line, bar, etc.
    private int displayedBars;                    // Number of bars to display
    private String title;                         // Chart title
    private boolean showVolume;                   // Whether to show volume
    private boolean showLegend;                   // Whether to show legend
    private Map<String, String> additionalOptions; // Extra customization options
}
```

## ChartType Enum

An enumeration of supported chart types.

```java
package com.dtech.algo.chart.config;

public enum ChartType {
    CANDLESTICK,  // Traditional OHLC candlestick chart
    LINE,         // Simple line chart (usually for close prices)
    BAR,          // OHLC bar chart
    AREA,         // Area chart (filled line chart)
    HOLLOW_CANDLE, // Hollow candlestick chart (bullish candles are hollow)
    HEIKIN_ASHI    // Heikin-Ashi candlestick chart (smoothed version of traditional candlestick)
}
```

## Additional Options

The `additionalOptions` map in `ChartConfig` allows for extended customization without changing the class structure. Some standard keys include:

- `"gridColor"` - Color for grid lines (e.g., "#CCCCCC")
- `"backgroundColor"` - Chart background color (e.g., "#FFFFFF")
- `"axisColor"` - Color for axis lines (e.g., "#000000")
- `"priceLineColor"` - Color for price lines (e.g., "#0000FF")
- `"volumeColor"` - Color for volume bars (e.g., "#8888FF")
- `"upColor"` - Color for bullish candles (e.g., "#00FF00")
- `"downColor"` - Color for bearish candles (e.g., "#FF0000")
- `"markerSize"` - Size for data point markers (e.g., "5")
- `"lineThickness"` - Thickness for lines (e.g., "1.5")

## Usage Guidelines

1. **Required Fields**:
   - `barSeries` - Must contain valid price data
   - `chartType` - Must specify a valid chart type

2. **Optional Fields with Defaults**:
   - `displayedBars` - Default is all available bars
   - `indicators` - Default is empty list (no indicators)
   - `showVolume` - Default is false
   - `showLegend` - Default is true
   - `title` - Default is "Chart"

3. **Best Practices**:
   - Use the builder pattern for clear and concise configuration
   - Group related indicators (e.g., all moving averages)
   - Use consistent colors for similar indicators
   - Set a descriptive title that includes the symbol and interval
   - Limit the number of indicators to prevent visual clutter

## Implementation Notes

- The class uses Lombok annotations to reduce boilerplate code
- All fields are private with public getters and setters
- The builder pattern is used for easy configuration
- The class is immutable once constructed
- Indicators are stored as a list to preserve order
