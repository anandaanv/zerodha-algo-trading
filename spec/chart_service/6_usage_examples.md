# Chart Service Usage Examples

## Overview

This document provides practical examples of how to use the Chart Service API for generating financial charts in the trading system.

## Example 1: Simple Price Chart with Moving Averages

This example demonstrates how to create a basic candlestick chart with two exponential moving averages.

```java
// Import necessary classes
import com.dtech.algo.service.ChartService;
import com.dtech.algo.chart.config.ChartConfig;
import com.dtech.algo.chart.config.ChartType;

// Get the price data
IntervalBarSeries barSeries = barSeriesHelper.getIntervalBarSeries("RELIANCE", "DAY");

// Create indicators
ClosePriceIndicator closePrice = new ClosePriceIndicator(barSeries);
EMAIndicator ema20 = new EMAIndicator(closePrice, 20);
EMAIndicator ema50 = new EMAIndicator(closePrice, 50);

// Create chart config
ChartConfig config = ChartConfig.builder()
    .barSeries(barSeries)
    .indicators(List.of(ema20, ema50))
    .chartType(ChartType.CANDLESTICK)
    .displayedBars(100)  // Show last 100 bars
    .title("RELIANCE Daily with EMAs")
    .showVolume(true)
    .build();

// Generate chart
String chartPath = chartService.generateChart(config, "reliance_daily");
System.out.println("Chart saved to: " + chartPath);
```

## Example 2: Multi-Chart with Different Indicators

This example shows how to create a multi-chart layout with different indicators and timeframes.

```java
// Get the price data for different timeframes
IntervalBarSeries dailySeries = barSeriesHelper.getIntervalBarSeries("RELIANCE", "DAY");
IntervalBarSeries hourlySeries = barSeriesHelper.getIntervalBarSeries("RELIANCE", "HOUR");

// Create indicators for daily chart
ClosePriceIndicator dailyClose = new ClosePriceIndicator(dailySeries);
EMAIndicator dailyEma20 = new EMAIndicator(dailyClose, 20);
RSIIndicator dailyRsi = new RSIIndicator(dailyClose, 14);

// Create indicators for hourly chart
ClosePriceIndicator hourlyClose = new ClosePriceIndicator(hourlySeries);
MACDIndicator hourlyMacd = new MACDIndicator(hourlyClose, 12, 26);
EMAIndicator hourlySignal = new EMAIndicator(hourlyMacd, 9);

// Create chart configs
ChartConfig dailyConfig = ChartConfig.builder()
    .barSeries(dailySeries)
    .indicators(List.of(dailyEma20))
    .chartType(ChartType.CANDLESTICK)
    .displayedBars(100)
    .title("RELIANCE Daily")
    .showVolume(true)
    .build();

ChartConfig rsiConfig = ChartConfig.builder()
    .barSeries(dailySeries)
    .indicators(List.of(dailyRsi))
    .chartType(ChartType.LINE)
    .displayedBars(100)
    .title("RELIANCE RSI (14)")
    .showVolume(false)
    .build();

ChartConfig hourlyConfig = ChartConfig.builder()
    .barSeries(hourlySeries)
    .indicators(List.of())
    .chartType(ChartType.CANDLESTICK)
    .displayedBars(100)
    .title("RELIANCE Hourly")
    .showVolume(true)
    .build();

ChartConfig macdConfig = ChartConfig.builder()
    .barSeries(hourlySeries)
    .indicators(List.of(hourlyMacd, hourlySignal))
    .chartType(ChartType.LINE)
    .displayedBars(100)
    .title("RELIANCE MACD (12,26,9)")
    .showVolume(false)
    .build();

// Generate multi-chart
String chartPath = chartService.generateCharts(
    List.of(dailyConfig, rsiConfig, hourlyConfig, macdConfig),
    "reliance_analysis"
);
System.out.println("Multi-chart saved to: " + chartPath);
```

## Example 3: Custom Chart Styling

This example demonstrates how to use custom styling options for charts.

```java
// Get the price data
IntervalBarSeries barSeries = barSeriesHelper.getIntervalBarSeries("INFY", "DAY");

// Create indicators
ClosePriceIndicator closePrice = new ClosePriceIndicator(barSeries);
BollingerBandsMiddleIndicator middleBand = new BollingerBandsMiddleIndicator(new SMAIndicator(closePrice, 20));
BollingerBandsUpperIndicator upperBand = new BollingerBandsUpperIndicator(middleBand, new StandardDeviationIndicator(closePrice, 20), 2);
BollingerBandsLowerIndicator lowerBand = new BollingerBandsLowerIndicator(middleBand, new StandardDeviationIndicator(closePrice, 20), 2);

// Create custom styling options
Map<String, String> customOptions = new HashMap<>();
customOptions.put("backgroundColor", "#F5F5F5");  // Light gray background
customOptions.put("gridColor", "#CCCCCC");        // Light gray grid
customOptions.put("upColor", "#00AA00");          // Dark green for up candles
customOptions.put("downColor", "#CC0000");        // Dark red for down candles
customOptions.put("volumeColor", "#8888FF");      // Blue volume bars

// Create chart config with custom options
ChartConfig config = ChartConfig.builder()
    .barSeries(barSeries)
    .indicators(List.of(middleBand, upperBand, lowerBand))
    .chartType(ChartType.CANDLESTICK)
    .displayedBars(100)
    .title("INFY Daily with Bollinger Bands")
    .showVolume(true)
    .showLegend(true)
    .additionalOptions(customOptions)
    .build();

// Generate chart
String chartPath = chartService.generateChart(config, "infy_bollinger");
System.out.println("Styled chart saved to: " + chartPath);
```

## Example 4: Bar Chart with Support and Resistance

This example shows how to create a bar chart with support and resistance levels.

```java
// Get the price data
IntervalBarSeries barSeries = barSeriesHelper.getIntervalBarSeries("HDFCBANK", "DAY");

// Create custom indicators for support and resistance levels
ConstantIndicator<Num> resistance = new ConstantIndicator<>(barSeries.numOf(1650.0));
ConstantIndicator<Num> support = new ConstantIndicator<>(barSeries.numOf(1550.0));

// Create custom styling
Map<String, String> customOptions = new HashMap<>();
customOptions.put("resistanceColor", "#FF0000");  // Red for resistance
customOptions.put("supportColor", "#00FF00");     // Green for support
customOptions.put("resistanceStyle", "DASH");     // Dashed line
customOptions.put("supportStyle", "DASH");        // Dashed line

// Create chart config
ChartConfig config = ChartConfig.builder()
    .barSeries(barSeries)
    .indicators(List.of(resistance, support))
    .chartType(ChartType.BAR)
    .displayedBars(60)  // Show last 60 bars (approximately 3 months of trading days)
    .title("HDFCBANK Daily with Support/Resistance")
    .showVolume(true)
    .additionalOptions(customOptions)
    .build();

// Generate chart
String chartPath = chartService.generateChart(config, "hdfc_support_resistance");
System.out.println("Support/Resistance chart saved to: " + chartPath);
```

## Example 5: Heikin-Ashi Chart for Trend Analysis

This example demonstrates how to create a Heikin-Ashi chart for better trend visualization.

```java
// Get the price data
IntervalBarSeries barSeries = barSeriesHelper.getIntervalBarSeries("TCS", "DAY");

// Create indicators
ClosePriceIndicator closePrice = new ClosePriceIndicator(barSeries);
SMAIndicator sma50 = new SMAIndicator(closePrice, 50);
SMAIndicator sma200 = new SMAIndicator(closePrice, 200);

// Create chart config
ChartConfig config = ChartConfig.builder()
    .barSeries(barSeries)
    .indicators(List.of(sma50, sma200))
    .chartType(ChartType.HEIKIN_ASHI)  // Use Heikin-Ashi candles
    .displayedBars(200)  // Show more bars for trend analysis
    .title("TCS Daily (Heikin-Ashi) with SMAs")
    .showVolume(true)
    .build();

// Generate chart
String chartPath = chartService.generateChart(config, "tcs_heikin_ashi");
System.out.println("Heikin-Ashi chart saved to: " + chartPath);
```

## Best Practices

1. **Limit the number of indicators** on a single chart to prevent visual clutter
2. **Use appropriate chart types** for different analyses:
   - Candlestick/Bar charts for price action analysis
   - Line charts for smoother trend visualization
   - Heikin-Ashi for trend analysis with reduced noise
3. **Group related indicators** into separate charts in a multi-chart layout
4. **Use consistent colors** for the same indicators across different charts
5. **Set descriptive titles** that include the symbol, timeframe, and main indicators
6. **Limit the number of displayed bars** to improve readability (typically 50-200 bars)
7. **Show volume** for price charts but hide it for indicator-only charts
8. **Use custom styling** to improve visual clarity and highlight important information
