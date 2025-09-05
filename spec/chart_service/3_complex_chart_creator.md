# ComplexChartCreator Specification

## Overview

The `ComplexChartCreator` class is responsible for creating individual chart components with indicators. It is a core component of the chart service that translates `ChartConfig` objects into rendered JFreeChart instances with a focus on generating charts that are both visually informative and easily interpretable by AI systems like OpenAI's GPT models.

## Class Definition

```java
package com.dtech.algo.chart;

import com.dtech.algo.chart.config.ChartConfig;
import com.dtech.algo.chart.config.ChartType;
import com.dtech.algo.chart.renderer.ChartRendererFactory;
import com.dtech.algo.series.IntervalBarSeries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.data.xy.XYDataset;
import org.jfree.chart.encoders.SunPNGEncoderAdapter;
import org.jfree.graphics2d.svg.SVGGraphics2D;
import org.springframework.stereotype.Component;
import org.ta4j.core.indicators.CachedIndicator;

import java.awt.Rectangle;
import java.awt.Graphics2D;
import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class ComplexChartCreator {

    private final ChartRendererFactory rendererFactory;
    private final IndicatorCategoryService indicatorCategoryService;

    /**
     * Create a JFreeChart from the provided configuration
     * @param config Chart configuration with bar series and indicators
     * @return A configured JFreeChart object
     */
    public JFreeChart createChart(ChartConfig config) {
        // Implementation details
        // 1. Create dataset from bar series
        // 2. Choose renderer based on chart type
        // 3. Configure chart appearance
        // 4. Add indicators
        // 5. Return fully configured chart
    }

    /**
     * Export chart as SVG string with semantic metadata for AI interpretation
     * @param chart The JFreeChart to export
     * @param config The original chart configuration
     * @return SVG representation of the chart with metadata
     */
    public String exportChartAsSVG(JFreeChart chart, ChartConfig config) {
        // Implementation details
        // 1. Create SVG Graphics object
        // 2. Draw chart to SVG
        // 3. Add semantic metadata
        // 4. Return SVG as string
    }

    /**
     * Export chart as PNG byte array
     * @param chart The JFreeChart to export
     * @param width Desired width of the image
     * @param height Desired height of the image
     * @return PNG image as byte array
     */
    public byte[] exportChartAsPNG(JFreeChart chart, int width, int height) {
        // Implementation details
        // 1. Create buffered image
        // 2. Draw chart to image
        // 3. Encode as PNG
        // 4. Return byte array
    }

    /**
     * Add indicators to a chart
     * @param chart The JFreeChart to add indicators to
     * @param config Configuration containing the indicators
     */
    private void addIndicatorsToChart(JFreeChart chart, ChartConfig config) {
        // Implementation details
        // 1. Categorize indicators (overlay vs. separate panel)
        // 2. Add overlay indicators to main price panel
        // 3. Create separate panels for other indicators
        // 4. Configure indicator appearance
    }

    /**
     * Create a dataset from bar series
     * @param barSeries The bar series to use
     * @param displayedBars Number of bars to display
     * @return A dataset appropriate for the chart type
     */
    private XYDataset createDataset(IntervalBarSeries barSeries, int displayedBars) {
        // Implementation details
        // 1. Extract relevant portion of bar series
        // 2. Create appropriate dataset type based on chart type
        // 3. Populate dataset with bar data
        // 4. Return dataset
    }

    /**
     * Add semantic metadata to SVG for AI interpretation
     * @param svgGraphics SVG graphics object
     * @param config Chart configuration
     * @param dataSummary Summary statistics of the data
     */
    private void addSemanticMetadata(SVGGraphics2D svgGraphics, ChartConfig config, Map<String, Object> dataSummary) {
        // Implementation details
        // 1. Add chart type and time range metadata
        // 2. Add indicator information
        // 3. Add key statistics (min, max, average, etc.)
        // 4. Add detected patterns if available
    }
}
```

## Implementation Details

The `ComplexChartCreator` class in the chart package will:

1. **Create JFreeChart objects** from each ChartConfig provided
   - Select appropriate renderer for the chart type
   - Set up axes, titles, and legends
   - Configure chart appearance based on configuration options

2. **Handle rendering of different chart types**
   - Use strategy pattern via ChartRendererFactory to support different chart types
   - Apply type-specific styling and visualization rules
   - Implement all major chart types (Candlestick, Line, Bar, Area, etc.)

3. **Add indicators to charts with appropriate styling**
   - Categorize indicators as overlay or separate panel
   - Support overlay indicators (Moving Averages, Bollinger Bands)
   - Support oscillator indicators (MACD, RSI, ADX) in separate panels
   - Apply appropriate colors and styles to indicators
   - Add clear legends for indicators

4. **Configure chart appearance for AI interpretability**
   - Set up clear date formatting for x-axis
   - Configure number formatting for price axis
   - Add explicit chart and axis titles
   - Configure grid lines and borders
   - Use high-contrast colors
   - Include semantic information in SVG metadata

5. **Export charts in AI-friendly formats**
   - Primary: SVG format with semantic metadata
   - Secondary: PNG/JPEG for human viewing
   - Include data summary in textual format

This class will leverage existing chart creation functionality from the codebase, particularly from:
- The elliott package
- Existing chart visualization classes (FlagVisualizer, ChartCreator, etc.)
- Specialized indicator visualizers (MACDVisualizer, RSIVisualizer)

## Key Methods

### createChart

This is the main method that transforms a ChartConfig into a JFreeChart:

1. Validate the config parameters
2. Create the appropriate dataset from the bar series
3. Select a renderer based on the chart type
4. Create the chart with proper axes and plot
5. Configure chart appearance (colors, grid, titles)
6. Add indicators to the chart
7. Add volume display if requested
8. Return the completed chart

### addIndicatorsToChart

This method adds technical indicators to the chart:

1. Categorize indicators as overlay or separate panel
2. For overlay indicators (like moving averages):
   - Add them to the main price plot
   - Use appropriate colors and line styles
3. For separate indicators (like oscillators):
   - Create a new plot panel below the main chart
   - Configure the indicator's appearance
4. Add legends for all indicators

### createDataset

This method creates a dataset from a bar series:

1. Extract the specified number of bars from the series
2. Create an appropriate dataset based on chart type:
   - OHLCDataset for candlestick and bar charts
   - XYSeries for line and area charts
3. Populate the dataset with data from the bar series
4. Apply any necessary transformations (e.g., Heikin-Ashi calculation)

## Integration with Renderers

The `ComplexChartCreator` will use a factory pattern to create appropriate renderers for different chart types:

```java
public interface ChartRenderer {
    JFreeChart renderChart(XYDataset dataset, ChartConfig config);
}
```

This allows for easy extension with new chart types in the future.

## AI Interpretability Features

To ensure charts are optimally interpretable by AI systems like OpenAI's GPT models, the following features will be implemented:

### 1. SVG Format with Semantic Metadata

- Export charts primarily as SVG (text-based format)
- Add structured metadata in SVG to describe:
  - Chart type and timeframe
  - Data range and scale
  - Indicator types and parameters
  - Key statistics (highs, lows, averages)
  - Detected patterns or signals

### 2. Data Structure Preservation

- Maintain clear structure in SVG between price data and indicators
- Group related elements (candles, volume bars)
- Use consistent and descriptive IDs/classes for elements
- Include explicit data values in metadata

### 3. Visual Optimization for AI

- High contrast colors for major elements
- Clear labeling of all axes and data series
- Consistent styling of similar elements
- Simplified visual design without unnecessary decorations
- Proper scaling to show important patterns

### 4. Technical Indicator Implementation

#### Overlay Indicators
These will be drawn directly on the price chart:
- Moving Averages (SMA, EMA, WMA)
- Bollinger Bands
- Keltner Channels
- Parabolic SAR
- Ichimoku Cloud

#### Separate Panel Indicators
These will be shown in separate panels below the main price chart:
- MACD (Moving Average Convergence Divergence)
  - MACD line
  - Signal line
  - Histogram
- RSI (Relative Strength Index)
  - RSI line
  - Overbought/oversold levels
- ADX (Average Directional Index)
  - ADX line
  - +DI and -DI lines
- Stochastic Oscillator
  - %K and %D lines
- Volume
  - Volume bars
  - Volume moving average

### 5. Data Summaries

For each chart, provide a textual summary with:
- Time range information
- Key price levels (open, high, low, close, avg)
- Indicator readings at latest bar
- Detected patterns or signals
- Volatility measures

This dual approach of visual charts with structured metadata will optimize the charts for both human and AI interpretation.
