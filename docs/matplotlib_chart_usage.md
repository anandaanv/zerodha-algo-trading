# Matplotlib Chart Creator Usage Guide

This document explains how to use the MatplotChartCreator component, which provides chart generation capabilities using Python's Matplotlib library.

## Overview

The MatplotChartCreator supports two modes of operation:

1. **Py4J Service Mode** (default): Uses a Py4J gateway to communicate with a long-running Python process
2. **Direct Execution Mode**: Executes the Python script directly for each chart request

## Configuration

The following configuration properties can be set in `application.properties`:

```properties
# Common settings
matplotlib.temp.directory=/tmp
matplotlib.python.script.path=src/main/python/matplotlib_chart.py

# Python executable path (used in both modes)
matplotlib.python.executable=/usr/bin/python3

# Py4J service mode settings
matplotlib.gateway.port=25333

# Mode selection
matplotlib.use.direct.execution=false
```

### Mode Selection

- Set `matplotlib.use.direct.execution=true` to use direct execution mode
- Set `matplotlib.use.direct.execution=false` to use Py4J service mode (default)

## Usage Examples

### Basic Chart Creation

```java
// Get a bar series from your data source
IntervalBarSeries barSeries = getBarSeries();

// Create a chart with default configuration
MatplotChartCreator chartCreator = new MatplotChartCreator();
byte[] chartImageData = chartCreator.createChart(barSeries);

// Save the chart to a file
try (FileOutputStream fos = new FileOutputStream("chart.png")) {
    fos.write(chartImageData);
}
```

### Custom Chart Configuration

```java
// Create a custom chart configuration
ChartConfig config = ChartConfig.builder()
        .chartType(ChartType.CANDLESTICK)
        .title("Custom Chart")
        .barSeries(barSeries)
        .showLegend(true)
        .showVolume(true)
        .build();

// Generate the chart
byte[] chartImageData = chartCreator.createChart(config);
```

## Mode Comparison

### Py4J Service Mode (Default)

**Advantages:**
- Better performance for multiple chart requests
- Single Python process initialization
- Persistent Python environment

**Disadvantages:**
- More complex setup
- Potential for gateway communication issues
- Requires Py4J dependency

### Direct Execution Mode

**Advantages:**
- Simpler architecture
- No gateway communication issues
- Isolated Python process for each chart
- More reliable in some environments

**Disadvantages:**
- Slower for multiple chart requests (Python startup overhead)
- No persistent Python environment

## Python Dependencies

The Python script requires the following dependencies:

```
pip install matplotlib pandas numpy mplfinance
```

If using Py4J service mode, you also need:

```
pip install py4j
```

## Troubleshooting

### Python Script Not Found

Ensure the Python script path is correctly set in the configuration:

```properties
matplotlib.python.script.path=src/main/python/matplotlib_chart.py
```

### Python Executable Not Found

Ensure the Python executable path is correctly set:

```properties
matplotlib.python.executable=/usr/bin/python3
```

### Missing Python Dependencies

Install the required Python dependencies:

```
pip install matplotlib pandas numpy mplfinance py4j
```

### Gateway Connection Issues

If you experience gateway connection issues in Py4J service mode, try switching to direct execution mode:

```properties
matplotlib.use.direct.execution=true
```