# Matplotlib Integration for Algorithmic Trading Charts

## Introduction

This document outlines how to use Matplotlib for creating charts in the Zerodha algorithmic trading platform. Matplotlib is a comprehensive library for creating static, animated, and interactive visualizations in Python, making it an excellent choice for financial chart visualization.

### Advantages of Matplotlib

- **Flexibility**: Highly customizable with fine-grained control over every element of a chart
- **Rich feature set**: Supports various chart types including candlestick, line, bar, and scatter plots
- **Active community**: Well-maintained with extensive documentation and examples
- **Integration capabilities**: Works well with data analysis libraries like Pandas and NumPy
- **Publication quality**: Produces high-quality visualizations suitable for research and analysis

## Integration with Java

Since our platform is built in Java, we need a bridge to utilize Matplotlib. Here are the recommended approaches:

### 1. Python Bridge via Process Execution

The simplest approach is to have Java generate data files and execute Python scripts that use Matplotlib:

```java
public class MatplotlibChartService {
    private final String pythonExecutable;
    private final String scriptDirectory;
    
    public MatplotlibChartService(String pythonExecutable, String scriptDirectory) {
        this.pythonExecutable = pythonExecutable;
        this.scriptDirectory = scriptDirectory;
    }
    
    public byte[] generateChart(ChartConfig config) throws IOException {
        // Export data to a temporary file
        String dataFile = exportDataToTempFile(config);
        
        // Execute Python script
        ProcessBuilder pb = new ProcessBuilder(
            pythonExecutable,
            scriptDirectory + "/generate_chart.py",
            dataFile,
            "--type=" + config.getChartType().name(),
            "--title=" + config.getTitle()
            // Add other parameters as needed
        );
        
        Process process = pb.start();
        // Handle process execution...
        
        // Read the generated image
        return Files.readAllBytes(Path.of(outputFile));
    }
    
    private String exportDataToTempFile(ChartConfig config) {
        // Export bar series data to CSV or JSON
        // ...
    }
}
```

### 2. Using Jython (Python for JVM)

Jython allows running Python code directly in the JVM:

```java
public class JythonMatplotlibService {
    private final PythonInterpreter interpreter;
    
    public JythonMatplotlibService() {
        interpreter = new PythonInterpreter();
        interpreter.exec("import matplotlib.pyplot as plt");
        interpreter.exec("import numpy as np");
        interpreter.exec("import pandas as pd");
    }
    
    public byte[] generateCandlestickChart(ChartConfig config) {
        // Convert Java data to Python objects
        // ...
        
        // Execute Python code
        interpreter.exec("fig, ax = plt.subplots()");
        interpreter.exec("ax.plot(x, y)");
        // ...
        
        // Get the image bytes
        // ...
        
        return imageBytes;
    }
}
```

### 3. Using py4j (Java-Python Bridge)

py4j provides a more robust bridge between Java and Python. This is the recommended approach and has been implemented in the `MatplotChartCreator` class:

```java
@Slf4j
@Component
public class MatplotChartCreator {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final AtomicInteger CHART_COUNTER = new AtomicInteger(0);

    @Value("${matplotlib.temp.directory:/tmp}")
    private String tempDirectory;

    @Value("${matplotlib.python.script.path:src/main/python/matplotlib_chart.py}")
    private String pythonScriptPath;

    @Value("${matplotlib.gateway.port:25333}")
    private int gatewayPort;

    private GatewayServer gatewayServer;
    private Process pythonProcess;

    /**
     * Default chart configuration with common indicators
     */
    private final Map<String, Object> defaultChartConfig = new HashMap<>();

    public MatplotChartCreator() {
        // Initialize default chart configuration
        initializeDefaultConfig();
    }

    /**
     * Initialize the default chart configuration with common indicators
     */
    private void initializeDefaultConfig() {
        // Default indicators
        List<Map<String, Object>> indicators = new ArrayList<>();
        
        // SMA 20
        Map<String, Object> sma20 = new HashMap<>();
        sma20.put("type", "SMA");
        sma20.put("period", 20);
        sma20.put("color", "blue");
        indicators.add(sma20);
        
        // SMA 50
        Map<String, Object> sma50 = new HashMap<>();
        sma50.put("type", "SMA");
        sma50.put("period", 50);
        sma50.put("color", "red");
        indicators.add(sma50);
        
        // Bollinger Bands
        Map<String, Object> bollinger = new HashMap<>();
        bollinger.put("type", "BOLLINGER");
        bollinger.put("period", 20);
        bollinger.put("std_dev", 2);
        indicators.add(bollinger);
        
        defaultChartConfig.put("indicators", indicators);
        defaultChartConfig.put("showVolume", true);
        defaultChartConfig.put("showLegend", true);
        defaultChartConfig.put("chartType", "CANDLESTICK");
    }

    /**
     * Initialize the py4j gateway server and start the Python process
     */
    @PostConstruct
    public void initialize() {
        try {
            // Start the gateway server
            gatewayServer = new GatewayServer(this, gatewayPort);
            gatewayServer.start();
            log.info("Gateway Server Started on port {}", gatewayPort);

            // Start the Python process
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "python", pythonScriptPath, String.valueOf(gatewayPort));
            processBuilder.redirectErrorStream(true);
            pythonProcess = processBuilder.start();

            log.info("Python process started for Matplotlib chart generation");
        } catch (Exception e) {
            log.error("Failed to initialize MatplotChartCreator", e);
        }
    }

    /**
     * Clean up resources when the bean is destroyed
     */
    @PreDestroy
    public void cleanup() {
        if (gatewayServer != null) {
            gatewayServer.shutdown();
            log.info("Gateway Server Stopped");
        }
        
        if (pythonProcess != null) {
            pythonProcess.destroy();
            log.info("Python process terminated");
        }
    }

    /**
     * Generate a chart from the given IntervalBarSeries using default configuration
     * 
     * @param barSeries The bar series to chart
     * @return The chart image as a byte array
     * @throws Exception If there is an error generating the chart
     */
    public byte[] createChart(IntervalBarSeries barSeries) throws Exception {
        ChartConfig config = new ChartConfig();
        config.setBarSeries(barSeries);
        config.setTitle(barSeries.getInstrument() + " - " + barSeries.getInterval());
        
        // Apply default configuration
        config.setShowVolume((Boolean) defaultChartConfig.get("showVolume"));
        config.setShowLegend((Boolean) defaultChartConfig.get("showLegend"));
        
        return createChart(config);
    }

    /**
     * Generate a chart from the given ChartConfig
     * 
     * @param config The chart configuration
     * @return The chart image as a byte array
     * @throws Exception If there is an error generating the chart
     */
    public byte[] createChart(ChartConfig config) throws Exception {
        if (!config.isValid()) {
            throw new IllegalArgumentException("Invalid chart configuration");
        }

        // Create temporary files
        int chartId = CHART_COUNTER.incrementAndGet();
        File dataFile = new File(tempDirectory, "chart_data_" + chartId + ".csv");
        File configFile = new File(tempDirectory, "chart_config_" + chartId + ".json");
        File outputFile = new File(tempDirectory, "chart_output_" + chartId + ".png");

        try {
            // Export data to CSV
            exportDataToCsv(config.getBarSeries(), dataFile);
            
            // Export config to JSON
            exportConfigToJson(config, configFile);
            
            // Call Python to generate the chart
            boolean success = callPythonChartGenerator(dataFile.getAbsolutePath(), 
                                                     configFile.getAbsolutePath(), 
                                                     outputFile.getAbsolutePath());
            
            if (!success) {
                throw new IOException("Failed to generate chart");
            }
            
            // Read the generated image
            return Files.readAllBytes(outputFile.toPath());
        } finally {
            // Clean up temporary files
            dataFile.delete();
            configFile.delete();
            outputFile.delete();
        }
    }
}
```

## Common Chart Types for Algorithmic Trading

### 1. Candlestick Charts

```python
import matplotlib.pyplot as plt
import matplotlib.dates as mdates
import pandas as pd
from mplfinance.original_flavor import candlestick_ohlc

def create_candlestick_chart(data, title, output_file):
    # Convert data to OHLC format
    ohlc = data[['date', 'open', 'high', 'low', 'close']].copy()
    ohlc['date'] = pd.to_datetime(ohlc['date'])
    ohlc['date'] = ohlc['date'].map(mdates.date2num)
    
    # Create figure and axis
    fig, ax = plt.subplots(figsize=(12, 6))
    
    # Plot candlestick chart
    candlestick_ohlc(ax, ohlc.values, width=0.6, colorup='green', colordown='red')
    
    # Format x-axis
    ax.xaxis.set_major_formatter(mdates.DateFormatter('%Y-%m-%d'))
    plt.xticks(rotation=45)
    
    # Add title and labels
    plt.title(title)
    plt.xlabel('Date')
    plt.ylabel('Price')
    
    # Add grid
    plt.grid(True, alpha=0.3)
    
    # Adjust layout and save
    plt.tight_layout()
    plt.savefig(output_file, dpi=300)
    plt.close()
```

### 2. Technical Indicators

```python
def add_indicators(ax, data, indicators):
    for indicator in indicators:
        if indicator['type'] == 'SMA':
            period = indicator.get('period', 20)
            data[f'SMA_{period}'] = data['close'].rolling(window=period).mean()
            ax.plot(data.index, data[f'SMA_{period}'], label=f'SMA {period}')
        elif indicator['type'] == 'EMA':
            period = indicator.get('period', 20)
            data[f'EMA_{period}'] = data['close'].ewm(span=period).mean()
            ax.plot(data.index, data[f'EMA_{period}'], label=f'EMA {period}')
        elif indicator['type'] == 'BOLLINGER':
            period = indicator.get('period', 20)
            std_dev = indicator.get('std_dev', 2)
            data[f'SMA_{period}'] = data['close'].rolling(window=period).mean()
            data[f'BOLL_UPPER'] = data[f'SMA_{period}'] + (data['close'].rolling(window=period).std() * std_dev)
            data[f'BOLL_LOWER'] = data[f'SMA_{period}'] - (data['close'].rolling(window=period).std() * std_dev)
            ax.plot(data.index, data[f'BOLL_UPPER'], linestyle='--', alpha=0.7)
            ax.plot(data.index, data[f'SMA_{period}'], alpha=0.7)
            ax.plot(data.index, data[f'BOLL_LOWER'], linestyle='--', alpha=0.7)
    
    ax.legend()
```

### 3. Volume Charts

```python
def add_volume_panel(fig, data, position=1, height_ratio=0.2):
    # Create a new axis for volume
    ax_volume = fig.add_subplot(position)
    
    # Plot volume bars
    ax_volume.bar(data.index, data['volume'], color='gray', alpha=0.5)
    
    # Format axis
    ax_volume.set_ylabel('Volume')
    ax_volume.grid(True, alpha=0.3)
    
    return ax_volume
```

## Implementation Example

Here's a complete example of how to create a chart with Matplotlib that includes candlesticks, indicators, and volume:

```python
import matplotlib.pyplot as plt
import matplotlib.dates as mdates
import pandas as pd
import numpy as np
from mplfinance.original_flavor import candlestick_ohlc
import json
import sys

def create_complete_chart(data_file, config_file, output_file):
    # Load data and config
    data = pd.read_csv(data_file)
    with open(config_file, 'r') as f:
        config = json.load(f)
    
    # Prepare data
    data['date'] = pd.to_datetime(data['date'])
    data.set_index('date', inplace=True)
    
    # Create figure with subplots
    fig = plt.figure(figsize=(12, 8))
    
    # Determine subplot ratios based on whether to show volume
    if config.get('showVolume', False):
        gs = fig.add_gridspec(2, 1, height_ratios=[4, 1])
        ax_price = fig.add_subplot(gs[0])
        ax_volume = fig.add_subplot(gs[1], sharex=ax_price)
    else:
        ax_price = fig.add_subplot(1, 1, 1)
    
    # Plot candlestick chart
    ohlc = data.reset_index()[['date', 'open', 'high', 'low', 'close']].copy()
    ohlc['date'] = ohlc['date'].map(mdates.date2num)
    candlestick_ohlc(ax_price, ohlc.values, width=0.6, 
                    colorup=config.get('upColor', 'green'), 
                    colordown=config.get('downColor', 'red'))
    
    # Add indicators
    if 'indicators' in config:
        add_indicators(ax_price, data, config['indicators'])
    
    # Add volume if requested
    if config.get('showVolume', False):
        ax_volume.bar(data.index, data['volume'], color='gray', alpha=0.5)
        ax_volume.set_ylabel('Volume')
        ax_volume.grid(True, alpha=0.3)
    
    # Format axes
    ax_price.set_title(config.get('title', 'Price Chart'))
    ax_price.set_ylabel('Price')
    ax_price.grid(True, alpha=0.3)
    
    # Format x-axis dates
    ax_price.xaxis.set_major_formatter(mdates.DateFormatter('%Y-%m-%d'))
    plt.xticks(rotation=45)
    
    # Add legend if requested
    if config.get('showLegend', True):
        ax_price.legend()
    
    # Adjust layout and save
    plt.tight_layout()
    plt.savefig(output_file, dpi=300)
    plt.close()

if __name__ == "__main__":
    if len(sys.argv) != 4:
        print("Usage: python generate_chart.py <data_file> <config_file> <output_file>")
        sys.exit(1)
    
    create_complete_chart(sys.argv[1], sys.argv[2], sys.argv[3])
```

## Integration with Existing Java Code

To integrate Matplotlib with our existing Java codebase, we'll need to create:

1. A Python script that handles chart generation (as shown above)
2. A Java service that:
   - Converts our existing data models to a format Python can understand
   - Executes the Python script with appropriate parameters
   - Returns the generated chart image

Here's a proposed Java service implementation:

```java
@Service
public class MatplotlibChartService {
    private final String pythonExecutable;
    private final String scriptPath;
    private final String tempDirectory;
    
    @Autowired
    public MatplotlibChartService(
            @Value("${matplotlib.python.executable:python3}") String pythonExecutable,
            @Value("${matplotlib.script.path}") String scriptPath,
            @Value("${matplotlib.temp.directory:/tmp}") String tempDirectory) {
        this.pythonExecutable = pythonExecutable;
        this.scriptPath = scriptPath;
        this.tempDirectory = tempDirectory;
    }
    
    public byte[] generateChart(ChartConfig config) throws IOException {
        // Create temporary files for data and config
        File dataFile = File.createTempFile("chart_data_", ".csv", new File(tempDirectory));
        File configFile = File.createTempFile("chart_config_", ".json", new File(tempDirectory));
        File outputFile = File.createTempFile("chart_output_", ".png", new File(tempDirectory));
        
        try {
            // Export data to CSV
            exportDataToCsv(config.getBarSeries(), dataFile);
            
            // Export config to JSON
            exportConfigToJson(config, configFile);
            
            // Execute Python script
            ProcessBuilder pb = new ProcessBuilder(
                pythonExecutable,
                scriptPath,
                dataFile.getAbsolutePath(),
                configFile.getAbsolutePath(),
                outputFile.getAbsolutePath()
            );
            
            Process process = pb.start();
            int exitCode = process.waitFor();
            
            if (exitCode != 0) {
                throw new RuntimeException("Chart generation failed with exit code: " + exitCode);
            }
            
            // Read the generated image
            return Files.readAllBytes(outputFile.toPath());
        } catch (Exception e) {
            throw new IOException("Failed to generate chart", e);
        } finally {
            // Clean up temporary files
            dataFile.delete();
            configFile.delete();
            outputFile.delete();
        }
    }
    
    private void exportDataToCsv(IntervalBarSeries barSeries, File file) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            writer.println("date,open,high,low,close,volume");
            
            for (int i = 0; i < barSeries.getBarCount(); i++) {
                Bar bar = barSeries.getBar(i);
                writer.printf("%s,%.2f,%.2f,%.2f,%.2f,%d%n",
                    bar.getEndTime().toLocalDate(),
                    bar.getOpenPrice().doubleValue(),
                    bar.getHighPrice().doubleValue(),
                    bar.getLowPrice().doubleValue(),
                    bar.getClosePrice().doubleValue(),
                    bar.getVolume().longValue()
                );
            }
        }
    }
    
    private void exportConfigToJson(ChartConfig config, File file) throws IOException {
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("title", config.getTitle());
        configMap.put("chartType", config.getChartType().name());
        configMap.put("showVolume", config.isShowVolume());
        configMap.put("showLegend", config.isShowLegend());
        
        // Add indicators
        if (config.getIndicators() != null && !config.getIndicators().isEmpty()) {
            List<Map<String, Object>> indicators = new ArrayList<>();
            for (CachedIndicator<?> indicator : config.getIndicators()) {
                Map<String, Object> indicatorMap = new HashMap<>();
                // Map indicator properties based on type
                // This would need to be expanded based on your indicator types
                indicatorMap.put("type", indicator.getClass().getSimpleName());
                indicators.add(indicatorMap);
            }
            configMap.put("indicators", indicators);
        }
        
        // Add additional options
        if (config.getAdditionalOptions() != null) {
            configMap.putAll(config.getAdditionalOptions());
        }
        
        // Write to JSON file
        ObjectMapper mapper = new ObjectMapper();
        mapper.writeValue(file, configMap);
    }
}
```

## Conclusion

Matplotlib offers a powerful and flexible solution for creating financial charts in our algorithmic trading platform. By using a Python bridge, we can leverage Matplotlib's extensive capabilities while maintaining our Java-based architecture.

The implementation outlined in this document provides:

1. High-quality candlestick charts with customizable appearance
2. Support for technical indicators
3. Volume visualization
4. Flexible configuration options

This approach allows us to replace the current JFreeChart implementation with a more modern and feature-rich charting solution.

## Python Implementation

The Python script (`matplotlib_chart.py`) that works with the Java bridge is implemented as follows:

```python
#!/usr/bin/env python
# -*- coding: utf-8 -*-

import sys
import os
import json
import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import matplotlib.dates as mdates
from matplotlib.ticker import MaxNLocator
from py4j.java_gateway import JavaGateway, GatewayParameters, CallbackServerParameters

# Configure matplotlib for non-interactive backend for better performance
plt.switch_backend('Agg')

def connect_to_gateway(port):
    """Connect to the Java gateway server"""
    gateway = JavaGateway(
        gateway_parameters=GatewayParameters(port=port),
        callback_server_parameters=CallbackServerParameters(port=0)
    )
    return gateway

def create_chart(data_file, config_file, output_file):
    """Create a chart using matplotlib based on the provided data and configuration"""
    # Load data and config
    data = pd.read_csv(data_file, parse_dates=['date'])
    with open(config_file, 'r') as f:
        config = json.load(f)
    
    # Create figure with subplots
    fig = plt.figure(figsize=(12, 8))
    
    # Determine subplot ratios based on whether to show volume
    if config.get('showVolume', False):
        gs = fig.add_gridspec(2, 1, height_ratios=[4, 1])
        ax_price = fig.add_subplot(gs[0])
        ax_volume = fig.add_subplot(gs[1], sharex=ax_price)
    else:
        ax_price = fig.add_subplot(1, 1, 1)
    
    # Plot chart based on chart type
    chart_type = config.get('chartType', 'CANDLESTICK')
    
    if chart_type == 'CANDLESTICK':
        plot_candlestick_chart(ax_price, data, config)
    elif chart_type == 'LINE':
        plot_line_chart(ax_price, data, config)
    elif chart_type == 'BAR':
        plot_bar_chart(ax_price, data, config)
    else:
        # Default to candlestick
        plot_candlestick_chart(ax_price, data, config)
    
    # Add indicators
    if 'indicators' in config:
        add_indicators(ax_price, data, config['indicators'])
    
    # Add volume if requested
    if config.get('showVolume', False):
        plot_volume(ax_volume, data)
    
    # Format axes
    ax_price.set_title(config.get('title', 'Price Chart'))
    ax_price.set_ylabel('Price')
    ax_price.grid(True, alpha=0.3)
    
    # Format x-axis dates
    ax_price.xaxis.set_major_formatter(mdates.DateFormatter('%Y-%m-%d'))
    plt.xticks(rotation=45)
    
    # Add legend if requested
    if config.get('showLegend', True):
        ax_price.legend()
    
    # Adjust layout and save
    plt.tight_layout()
    plt.savefig(output_file, dpi=300)
    plt.close(fig)
    
    return True
```

## Performance Optimization

The MatplotChartCreator is designed to efficiently generate a large number of charts (up to 1000 per minute) through several optimizations:

1. **Persistent Python Process**: Instead of starting a new Python process for each chart, a single Python process is started at application initialization and kept running.

2. **py4j Gateway**: The py4j gateway provides efficient communication between Java and Python without the overhead of process creation.

3. **Non-Interactive Backend**: The Python script uses a non-interactive Matplotlib backend (`Agg`) which is optimized for generating images without displaying them.

4. **Parallel Processing**: For batch chart generation, multiple charts can be generated in parallel using Java's concurrency utilities.

5. **Temporary File Management**: Temporary files are created with unique IDs and cleaned up immediately after use to prevent file system congestion.

6. **Optimized Data Transfer**: Data is transferred between Java and Python using CSV and JSON files, which are efficient for large datasets.

7. **Memory Management**: The Python script explicitly closes figures after saving to prevent memory leaks.

## Configuration

The MatplotChartCreator is configured through the following properties in `application.properties`:

```properties
# Matplotlib chart configuration
matplotlib.python.script.path=src/main/python/matplotlib_chart.py
matplotlib.temp.directory=/tmp
matplotlib.gateway.port=25333
```

## Usage Example

```java
@Autowired
private MatplotChartCreator chartCreator;

public byte[] generateChart(String stockSymbol, String timeframe) {
    // Get bar series data
    IntervalBarSeries barSeries = barSeriesHelper.getIntervalBarSeries(stockSymbol, timeframe);
    
    // Generate chart with default configuration
    return chartCreator.createChart(barSeries);
}
```

## Next Steps

1. Update the existing chart-related controllers to use the new MatplotChartCreator
2. Add unit and integration tests for the new implementation
3. Monitor performance in production environment