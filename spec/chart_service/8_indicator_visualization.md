# Technical Indicator Visualization Specification

## Overview

This document specifies the implementation details for visualizing various technical indicators in the chart service. Each indicator type requires specific rendering approaches to ensure clarity, accuracy, and AI interpretability.

## Indicator Categories

Indicators are divided into two main categories based on their visualization requirements:

1. **Overlay Indicators** - Displayed directly on the price chart
2. **Separate Panel Indicators** - Displayed in dedicated panels below the price chart

## Overlay Indicators

### Moving Averages

**Types:**
- Simple Moving Average (SMA)
- Exponential Moving Average (EMA)
- Weighted Moving Average (WMA)
- Hull Moving Average (HMA)

**Visualization:**
- Render as continuous lines on the price chart
- Use distinct colors for different periods (e.g., EMA20: blue, EMA50: red, EMA200: green)
- Line thickness proportional to significance (longer periods = thicker lines)
- Include period in legend (e.g., "EMA(20)")

**SVG Optimization:**
- Group all moving average elements with semantic class names
- Include period and type information as data attributes
- Add value labels at key points (chart edges)

### Bollinger Bands

**Components:**
- Upper Band (typically +2 standard deviations)
- Middle Band (typically SMA20)
- Lower Band (typically -2 standard deviations)

**Visualization:**
- Middle band as solid line
- Upper/lower bands as dashed or lighter lines
- Semi-transparent fill between bands (optional)
- Include parameters in legend (e.g., "BB(20,2)")

**SVG Optimization:**
- Group all band elements
- Include standard deviation and period parameters as data attributes
- Label width values at key points

### Keltner Channels

**Components:**
- Upper Band (typically EMA + ATR multiplier)
- Middle Band (typically EMA20)
- Lower Band (typically EMA - ATR multiplier)

**Visualization:**
- Similar to Bollinger Bands but with different color scheme
- Distinct visual differentiation from Bollinger Bands
- Include parameters in legend

**SVG Optimization:**
- Group all channel elements
- Include ATR multiplier and period parameters as data attributes

### Parabolic SAR

**Visualization:**
- Discrete dots above/below price
- Color indicating trend direction
- Size proportional to significance

**SVG Optimization:**
- Include trend direction and value as data attributes
- Group all SAR points

## Separate Panel Indicators

### MACD (Moving Average Convergence Divergence)

**Components:**
- MACD Line (difference between fast and slow EMAs)
- Signal Line (EMA of MACD Line)
- Histogram (difference between MACD and Signal Line)

**Visualization:**
- Dedicated panel below price chart
- MACD line (solid)
- Signal line (dashed)
- Histogram bars (positive/negative colors)
- Horizontal zero line
- Clear Y-axis scale

**SVG Optimization:**
- Group all MACD components
- Separate groups for line elements and histogram elements
- Include fast/slow/signal periods as metadata
- Mark crossovers and divergences

**Implementation Class:**
`MACDVisualizer` will handle the specialized rendering of MACD panels.

### RSI (Relative Strength Index)

**Components:**
- RSI Line
- Overbought level (typically 70)
- Oversold level (typically 30)
- Centerline (50)

**Visualization:**
- Dedicated panel below price chart
- RSI line with distinctive color
- Horizontal reference lines at 30, 50, and 70
- Semi-transparent fills for overbought/oversold regions
- Fixed Y-axis scale (0-100)

**SVG Optimization:**
- Include period parameter and current value as metadata
- Mark overbought/oversold conditions
- Highlight divergences if detected

**Implementation Class:**
`RSIVisualizer` will handle the specialized rendering of RSI panels.

### ADX (Average Directional Index)

**Components:**
- ADX Line (trend strength)
- +DI Line (positive directional indicator)
- -DI Line (negative directional indicator)

**Visualization:**
- Dedicated panel below price chart
- ADX line (usually black/gray)
- +DI line (usually green)
- -DI line (usually red)
- Horizontal reference line at 25 (trend strength threshold)
- Fixed Y-axis scale (0-100)

**SVG Optimization:**
- Include period parameter as metadata
- Mark trend changes and strength levels
- Group related components

**Implementation Class:**
`ADXVisualizer` will handle the specialized rendering of ADX panels.

### Volume

**Components:**
- Volume bars
- Volume moving average (optional)

**Visualization:**
- Dedicated panel below price chart
- Color-coded bars (up/down days)
- Optional moving average line
- Appropriate Y-axis scale with K/M suffixes

**SVG Optimization:**
- Include volume values as data attributes
- Mark significant volume spikes
- Group all volume elements

**Implementation Class:**
`VolumeVisualizer` will handle the specialized rendering of volume panels.

## Architecture

### Indicator Categorization Service

A service to determine the appropriate visualization category for each indicator:

```java
public class IndicatorCategoryService {
    /**
     * Categorize an indicator as overlay or separate panel
     * @param indicator The indicator to categorize
     * @return IndicatorCategory (OVERLAY or SEPARATE_PANEL)
     */
    public IndicatorCategory categorizeIndicator(CachedIndicator<?> indicator) {
        // Implementation details
    }

    /**
     * Get the appropriate visualizer for a separate panel indicator
     * @param indicator The indicator
     * @return The appropriate visualizer class
     */
    public Class<? extends IndicatorVisualizer> getVisualizerForIndicator(CachedIndicator<?> indicator) {
        // Implementation details
    }
}
```

### Indicator Visualizer Interface

A common interface for all indicator visualizers:

```java
public interface IndicatorVisualizer {
    /**
     * Create a plot for the indicator
     * @param indicator The indicator to visualize
     * @param barSeries The associated bar series
     * @return An XYPlot configured for the indicator
     */
    XYPlot createIndicatorPlot(CachedIndicator<?> indicator, IntervalBarSeries barSeries);

    /**
     * Add the indicator to an existing plot (for overlay indicators)
     * @param plot The plot to add the indicator to
     * @param indicator The indicator to add
     * @param barSeries The associated bar series
     */
    void addIndicatorToPlot(XYPlot plot, CachedIndicator<?> indicator, IntervalBarSeries barSeries);

    /**
     * Generate metadata about the indicator for AI interpretation
     * @param indicator The indicator
     * @return Map of metadata attributes
     */
    Map<String, Object> generateIndicatorMetadata(CachedIndicator<?> indicator);
}
```

## Implementation Strategy

1. Create specialized visualizer classes for each indicator type:
   - `MovingAverageVisualizer`
   - `BollingerBandVisualizer`
   - `MACDVisualizer`
   - `RSIVisualizer`
   - `ADXVisualizer`
   - `VolumeVisualizer`

2. Implement the `IndicatorCategoryService` to properly categorize indicators

3. Enhance `ComplexChartCreator` to:
   - Use the categorization service
   - Apply the appropriate visualizer for each indicator
   - Arrange separate panel indicators in a logical order
   - Ensure consistent styling across all indicators

4. Add metadata generation to each visualizer

## Testing Strategy

1. **Unit Tests**
   - Test indicator categorization logic
   - Test each visualizer independently
   - Verify metadata generation

2. **Integration Tests**
   - Test combining multiple indicators
   - Verify proper panel arrangement
   - Test with real market data

3. **Visual Verification**
   - Create test charts with all indicator types
   - Verify visual accuracy and clarity
   - Compare to reference implementations

4. **AI Interpretation Tests**
   - Verify SVG interpretability by AI systems
   - Test with edge cases (extreme values, gaps in data)

## Conclusion

By implementing specialized visualizers for each indicator type, we can ensure optimal rendering and AI interpretability. This approach allows for consistent styling while respecting the unique characteristics of each indicator type.
