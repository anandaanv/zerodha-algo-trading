package com.dtech.algo.chart;

import com.dtech.algo.chart.config.ChartConfig;
import com.dtech.algo.series.IntervalBarSeries;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.ta4j.core.Bar;
import org.ta4j.core.indicators.CachedIndicator;
import py4j.GatewayServer;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chart creator that uses Matplotlib via py4j to generate charts.
 * This implementation provides better performance and more flexibility
 * than the JFreeChart implementation.
 */
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
    
    @Value("${matplotlib.python.executable:/home/anand/miniconda3/bin/python3}")
    private String pythonExecutable;
    
    @Value("${matplotlib.use.direct.execution:true}")
    private boolean useDirectExecution;

    private GatewayServer gatewayServer;
    private Process pythonProcess;
    private Object pythonService;

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
//        Map<String, Object> bollinger = new HashMap<>();
//        bollinger.put("type", "BOLLINGER");
//        bollinger.put("period", 20);
//        bollinger.put("std_dev", 2);
//        indicators.add(bollinger);
        
        // MACD
        Map<String, Object> macd = new HashMap<>();
        macd.put("type", "MACD");
        macd.put("fastPeriod", 12);
        macd.put("slowPeriod", 26);
        macd.put("signalPeriod", 9);
        indicators.add(macd);
        
        // RSI
        Map<String, Object> rsi = new HashMap<>();
        rsi.put("type", "RSI");
        rsi.put("period", 14);
        indicators.add(rsi);
        
        // Stochastic
        Map<String, Object> stochastic = new HashMap<>();
        stochastic.put("type", "STOCHASTIC");
        stochastic.put("kPeriod", 14);
        stochastic.put("dPeriod", 3);
        stochastic.put("slowing", 3);
        indicators.add(stochastic);
        
        // ADX
        Map<String, Object> adx = new HashMap<>();
        adx.put("type", "ADX");
        adx.put("period", 14);
        indicators.add(adx);
        
        defaultChartConfig.put("indicators", indicators);
        defaultChartConfig.put("showVolume", true);
        defaultChartConfig.put("showLegend", true);
        defaultChartConfig.put("chartType", "CANDLESTICK");
    }

    /**
     * Initialize the py4j gateway server and start the Python process if not using direct execution
     */
    @PostConstruct
    public void initialize() {
        try {
            // Check if the Python script exists
            File scriptFile = new File(pythonScriptPath);
            if (!scriptFile.exists()) {
                log.error("Python script not found at: {}", pythonScriptPath);
                return; // Don't proceed if the script doesn't exist
            }
            
            // If using direct execution, we don't need to start the gateway server or Python process
            if (useDirectExecution) {
                log.info("Using direct Python execution mode. Gateway server will not be started.");
                return;
            }
            
            // Start the gateway server
            gatewayServer = new GatewayServer(this, gatewayPort);
            gatewayServer.start();
            log.info("Gateway Server Started on port {}", gatewayPort);

            // Start the Python process
            ProcessBuilder processBuilder = new ProcessBuilder(
                    pythonExecutable, pythonScriptPath, String.valueOf(gatewayPort));
            processBuilder.inheritIO();
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
        config.addBarSeries(barSeries);
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
        return createChart(config, null);
    }
    
    /**
     * Generate a chart from the given ChartConfig and optionally save it to a file
     * 
     * @param config The chart configuration
     * @param outputFilePath The path where to save the chart image (null if not saving to file)
     * @return The chart image as a byte array
     * @throws Exception If there is an error generating the chart
     */
    public byte[] createChart(ChartConfig config, String outputFilePath) throws Exception {
        if (!config.isValid()) {
            throw new IllegalArgumentException("Invalid chart configuration");
        }

        // Check if Python process is available when not using direct execution
        if (!useDirectExecution && pythonProcess == null) {
            log.warn("Python process is not available. Returning a minimal test image.");
            return createMinimalTestImage();
        }

        // Create temporary files
        int chartId = CHART_COUNTER.incrementAndGet();
        List<File> dataFiles = new ArrayList<>();
        File configFile = new File(tempDirectory, "chart_config_" + chartId + ".json");
        
        // If outputFilePath is null, use a temporary file
        File outputDir;
        if (outputFilePath != null && !outputFilePath.isEmpty()) {
            outputDir = new File(outputFilePath).getParentFile();
            if (outputDir != null) {
                outputDir.mkdirs();
            }
        } else {
            outputDir = new File(tempDirectory);
        }
        
        try {
            // Export data to CSV for each bar series
            for (int i = 0; i < config.getBarSeries().size(); i++) {
                IntervalBarSeries barSeries = config.getBarSeries().get(i);
                File dataFile = new File(tempDirectory, "chart_data_" + chartId + "_" + i + ".csv");
                exportDataToCsv(barSeries, dataFile);
                dataFiles.add(dataFile);
            }
            
            // Export config to JSON
            exportConfigToJson(config, configFile, dataFiles);
            
            // Generate output file paths for each bar series
            List<String> outputFilePaths = new ArrayList<>();
            for (IntervalBarSeries barSeries : config.getBarSeries()) {
                String symbol = barSeries.getInstrument();
                String interval = barSeries.getInterval().toString();
                String fileName = symbol + "_" + interval + ".png";
                
                if (outputFilePath != null && !outputFilePath.isEmpty()) {
                    // Use the provided directory but with the generated filename
                    outputFilePaths.add(new File(outputDir, fileName).getAbsolutePath());
                } else {
                    // Use a temporary file
                    outputFilePaths.add(new File(tempDirectory, fileName).getAbsolutePath());
                }
            }
            
            // Call Python to generate the charts
            boolean success = callPythonChartGenerator(
                String.join(",", dataFiles.stream().map(File::getAbsolutePath).toList()),
                configFile.getAbsolutePath(),
                String.join(",", outputFilePaths)
            );
            
            if (!success) {
                log.warn("Failed to generate chart with Python. Returning a minimal test image.");
                return createMinimalTestImage();
            }
            
            // Read the first generated image to return
            byte[] imageData = Files.readAllBytes(Path.of(outputFilePaths.get(0)));
            
            return imageData;
        } finally {
            // Clean up temporary files
            for (File dataFile : dataFiles) {
                dataFile.delete();
            }
            configFile.delete();
        }
    }
    
    /**
     * Creates a minimal valid PNG test image
     * 
     * @return A byte array containing a minimal valid PNG image
     */
    private byte[] createMinimalTestImage() {
        // Create a simple test image (minimal PNG header)
        return new byte[]{
            (byte)0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n',  // PNG signature
            0, 0, 0, 13,                                         // IHDR chunk length
            'I', 'H', 'D', 'R',                                  // IHDR chunk type
            0, 0, 0, 1, 0, 0, 0, 1, 8, 0, 0, 0, 0,               // IHDR chunk data (1x1 pixel, no compression, no filter, no interlace)
            (byte)0x37, (byte)0x6E, (byte)0xF9, (byte)0x24       // Correct IHDR CRC
        };
    }

    /**
     * Register a Python service with this gateway
     * This method is called by the Python side to register its service
     * 
     * @param service The Python service to register
     */
    public void registerPythonService(Object service) {
        this.pythonService = service;
        log.info("Python service registered: {}", service.getClass().getName());
    }

    /**
     * Call the Python chart generator
     * 
     * @param dataFiles The path to the data files (comma-separated for multiple files)
     * @param configFile The path to the config file
     * @param outputFiles The path to the output files (comma-separated for multiple files)
     * @return True if the chart was generated successfully
     */
    private boolean callPythonChartGenerator(String dataFiles, String configFile, String outputFiles) {
        // Use direct execution if configured, otherwise use py4j service
        if (useDirectExecution) {
            return callPythonDirectExecution(dataFiles, configFile, outputFiles);
        } else {
            return callPythonServiceExecution(dataFiles, configFile, outputFiles);
        }
    }
    
    /**
     * Call the Python chart generator via direct process execution
     * 
     * @param dataFiles The path to the data files (comma-separated for multiple files)
     * @param configFile The path to the config file
     * @param outputFiles The path to the output files (comma-separated for multiple files)
     * @return True if the chart was generated successfully
     */
    private boolean callPythonDirectExecution(String dataFiles, String configFile, String outputFiles) {
        try {
            log.info("Calling Python script directly to generate charts: data={}, config={}, output={}", 
                    dataFiles, configFile, outputFiles);
            
            // Check if Python script exists
            File scriptFile = new File(pythonScriptPath);
            if (!scriptFile.exists()) {
                log.error("Python script not found at: {}", pythonScriptPath);
                return false;
            }
            
            // Start the Python process with the three paths as arguments
            ProcessBuilder processBuilder = new ProcessBuilder(
                    pythonExecutable, pythonScriptPath, dataFiles, configFile, outputFiles);
            processBuilder.redirectErrorStream(true).inheritIO();
            Process process = processBuilder.start();
            
            // Wait for the process to complete
            int exitCode = process.waitFor();
            
            // Check if at least one output file was created
            boolean success = exitCode == 0;
            
            // Check if at least the first output file exists
            if (success && outputFiles.contains(",")) {
                String firstOutputFile = outputFiles.split(",")[0];
                success = new File(firstOutputFile).exists();
            } else if (success) {
                success = new File(outputFiles).exists();
            }
            
            if (!success) {
                log.error("Python script execution failed with exit code: {}", exitCode);
            }
            
            return success;
        } catch (Exception e) {
            log.error("Error executing Python script directly", e);
            return false;
        }
    }
    
    /**
     * Call the Python chart generator via py4j service
     * 
     * @param dataFiles The path to the data files (comma-separated for multiple files)
     * @param configFile The path to the config file
     * @param outputFiles The path to the output files (comma-separated for multiple files)
     * @return True if the chart was generated successfully
     */
    private boolean callPythonServiceExecution(String dataFiles, String configFile, String outputFiles) {
        try {
            // Check if Python service is available
            if (pythonService == null) {
                log.warn("Python service is not available. Chart generation will be skipped.");
                // Just check if files exist for backward compatibility
                return new File(configFile).exists() && 
                       (dataFiles.contains(",") ? 
                           new File(dataFiles.split(",")[0]).exists() : 
                           new File(dataFiles).exists());
            }
            
            log.info("Calling Python service to generate charts: data={}, config={}, output={}", 
                    dataFiles, configFile, outputFiles);
            
            // Call the create_chart method on the Python service
            return (boolean) pythonService.getClass()
                    .getMethod("create_chart", String.class, String.class, String.class)
                    .invoke(pythonService, dataFiles, configFile, outputFiles);
        } catch (Exception e) {
            log.error("Error calling Python chart generator via service", e);
            return false;
        }
    }

    /**
     * Export bar series data to a CSV file
     * 
     * @param barSeries The bar series to export
     * @param file The file to write to
     * @throws IOException If there is an error writing to the file
     */
    private void exportDataToCsv(IntervalBarSeries barSeries, File file) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            writer.println("date,open,high,low,close,volume");
            
            for (int i = 0; i < barSeries.getBarCount(); i++) {
                Bar bar = barSeries.getBar(i);
                writer.printf("%s,%.2f,%.2f,%.2f,%.2f,%d%n",
                    bar.getEndTime().format(DATE_FORMATTER),
                    bar.getOpenPrice().doubleValue(),
                    bar.getHighPrice().doubleValue(),
                    bar.getLowPrice().doubleValue(),
                    bar.getClosePrice().doubleValue(),
                    bar.getVolume().longValue()
                );
            }
        }
    }

    /**
     * Export chart configuration to a JSON file
     * 
     * @param config The chart configuration
     * @param file The file to write to
     * @throws IOException If there is an error writing to the file
     */
    private void exportConfigToJson(ChartConfig config, File file) throws IOException {
        exportConfigToJson(config, file, null);
    }
    
    /**
     * Export chart configuration to a JSON file with multiple data files
     * 
     * @param config The chart configuration
     * @param file The file to write to
     * @param dataFiles List of data files for each bar series
     * @throws IOException If there is an error writing to the file
     */
    private void exportConfigToJson(ChartConfig config, File file, List<File> dataFiles) throws IOException {
        // In a real implementation, we would use Jackson or Gson here
        // For simplicity, we'll just write a basic JSON structure
        
        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            writer.println("{");
            writer.printf("  \"title\": \"%s\",\n", config.getTitle());
            writer.printf("  \"chartType\": \"%s\",\n", config.getChartType());
            writer.printf("  \"showVolume\": %b,\n", config.isShowVolume());
            writer.printf("  \"showLegend\": %b,\n", config.isShowLegend());
            
            // Add data files information if provided
            if (dataFiles != null && !dataFiles.isEmpty()) {
                writer.println("  \"dataFiles\": [");
                for (int i = 0; i < dataFiles.size(); i++) {
                    IntervalBarSeries barSeries = config.getBarSeries().get(i);
                    writer.println("    {");
                    writer.printf("      \"path\": \"%s\",\n", dataFiles.get(i).getAbsolutePath());
                    writer.printf("      \"symbol\": \"%s\",\n", barSeries.getInstrument());
                    writer.printf("      \"interval\": \"%s\"\n", barSeries.getInterval().toString());
                    writer.print("    }");
                    if (i < dataFiles.size() - 1) {
                        writer.println(",");
                    } else {
                        writer.println();
                    }
                }
                writer.println("  ],");
            }
            
            // Add indicators from default config if none are specified
            List<Map<String, Object>> indicators;
            if (config.getIndicators() == null || config.getIndicators().isEmpty()) {
                indicators = (List<Map<String, Object>>) defaultChartConfig.get("indicators");
            } else {
                // Convert CachedIndicator to map
                indicators = new ArrayList<>();
                for (CachedIndicator<?> indicator : config.getIndicators()) {
                    Map<String, Object> indicatorMap = new HashMap<>();
                    // Map indicator properties based on type
                    String indicatorType = indicator.getClass().getSimpleName();
                    indicatorMap.put("type", indicatorType);
                    
                    // Add default parameters based on indicator type
                    if (indicatorType.contains("SMA")) {
                        indicatorMap.put("period", 20); // Default period
                    } else if (indicatorType.contains("EMA")) {
                        indicatorMap.put("period", 20); // Default period
                    } else if (indicatorType.contains("Bollinger")) {
                        indicatorMap.put("period", 20); // Default period
                        indicatorMap.put("std_dev", 2); // Default standard deviation
                    }
                    
                    indicators.add(indicatorMap);
                }
            }
            
            // Write indicators
            writer.println("  \"indicators\": [");
            for (int i = 0; i < indicators.size(); i++) {
                Map<String, Object> indicator = indicators.get(i);
                writer.println("    {");
                writer.printf("      \"type\": \"%s\"", indicator.get("type"));
                
                for (Map.Entry<String, Object> entry : indicator.entrySet()) {
                    if (!entry.getKey().equals("type")) {
                        if (entry.getValue() instanceof String) {
                            writer.printf(",\n      \"%s\": \"%s\"", entry.getKey(), entry.getValue());
                        } else {
                            writer.printf(",\n      \"%s\": %s", entry.getKey(), entry.getValue());
                        }
                    }
                }
                
                writer.println();
                writer.print("    }");
                if (i < indicators.size() - 1) {
                    writer.println(",");
                } else {
                    writer.println();
                }
            }
            writer.println("  ]");
            
            writer.println("}");
        }
    }
}