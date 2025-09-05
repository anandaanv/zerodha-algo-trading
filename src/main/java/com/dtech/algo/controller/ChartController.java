package com.dtech.algo.controller;

import com.dtech.algo.controller.dto.*;
import com.dtech.algo.service.ChartAnalysisService;

import com.dtech.algo.chart.ChartGrid;
import com.dtech.algo.chart.ComplexChartCreator;
import com.dtech.algo.chart.MatplotChartCreator;
import com.dtech.algo.chart.config.ChartConfig;
import com.dtech.algo.chart.config.ChartType;
import com.dtech.algo.series.ExtendedBarSeries;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.series.Interval;
import com.dtech.algo.series.SeriesType;
import com.dtech.algo.service.SignalType;
import com.dtech.algo.service.TradingViewChartService;
import com.dtech.kitecon.controller.BarSeriesHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import org.ta4j.core.Bar;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Controller for generating and serving chart images
 */
@Slf4j
@RestController
@RequestMapping("/api/charts")
@RequiredArgsConstructor
public class ChartController {

    private final MatplotChartCreator chartCreator;
    private final ComplexChartCreator complexChartCreator;
    private final ChartGrid chartGrid;
    private final BarSeriesHelper barSeriesHelper;
    private final ChartAnalysisService chartAnalysisService;
    private final TradingViewChartService tradingViewChartService;
    private final com.dtech.algo.service.ASTASignalService astaSignalService;
    private final com.dtech.algo.service.ASTAScreenService astaScreenService;

    @Value("${charts.temp.directory:./charts/temp}")
    private String chartsTempDirectory;

    /**
     * Generate a chart for a given instrument and interval
     *
     * @param symbol   The instrument symbol (e.g., "RELIANCE", "INFY")
     * @param interval The time interval (e.g., "day", "hour", "minute")
     * @return The chart image as a response entity
     */
    @GetMapping("/generate")
    public ResponseEntity<byte[]> generateChart(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "day") String interval) {

        try {
            log.info("Generating chart for symbol: {} with interval: {}", symbol, interval);

            // Get bar series data for the instrument
            IntervalBarSeries barSeries = barSeriesHelper.getIntervalBarSeries(symbol, interval);

            if (barSeries == null || barSeries.getBarCount() == 0) {
                log.warn("No data found for symbol: {} with interval: {}", symbol, interval);
                return ResponseEntity.notFound().build();
            }

            // Generate chart with default configuration
            byte[] chartImageData = chartCreator.createChart(barSeries);

            // Set response headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_PNG);
            headers.setContentLength(chartImageData.length);

            return new ResponseEntity<>(chartImageData, headers, HttpStatus.OK);

        } catch (Exception e) {
            log.error("Error generating chart for symbol: {} with interval: {}", symbol, interval, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Generate a customized chart with specific configuration options
     *
     * @param symbol     The instrument symbol (e.g., "RELIANCE", "INFY")
     * @param interval   The time interval (e.g., "day", "hour", "minute")
     * @param chartType  The chart type (e.g., "CANDLESTICK", "LINE", "BAR")
     * @param showVolume Whether to show volume (true/false)
     * @param showLegend Whether to show legend (true/false)
     * @param title      Custom chart title
     * @return The chart image as a response entity
     */
    @GetMapping("/custom")
    public ResponseEntity<byte[]> generateCustomChart(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "day") String interval,
            @RequestParam(defaultValue = "CANDLESTICK") String chartType,
            @RequestParam(defaultValue = "true") boolean showVolume,
            @RequestParam(defaultValue = "true") boolean showLegend,
            @RequestParam(required = false) String title) {

        try {
            log.info("Generating custom chart for symbol: {} with interval: {}", symbol, interval);

            // Get bar series data for the instrument
            IntervalBarSeries barSeries = barSeriesHelper.getIntervalBarSeries(symbol, interval);

            if (barSeries == null || barSeries.getBarCount() == 0) {
                log.warn("No data found for symbol: {} with interval: {}", symbol, interval);
                return ResponseEntity.notFound().build();
            }

            // Create chart configuration
            List<IntervalBarSeries> barSeriesList = new ArrayList<>();
            barSeriesList.add(barSeries);

            ChartConfig config = ChartConfig.builder()
                    .barSeries(barSeriesList)
                    .chartType(ChartType.valueOf(chartType.toUpperCase()))
                    .showVolume(showVolume)
                    .showLegend(showLegend)
                    .title(title != null ? title : symbol + " - " + interval)
                    .build();

            // Generate chart with custom configuration
            byte[] chartImageData = chartCreator.createChart(config);

            // Set response headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_PNG);
            headers.setContentLength(chartImageData.length);

            return new ResponseEntity<>(chartImageData, headers, HttpStatus.OK);

        } catch (Exception e) {
            log.error("Error generating custom chart for symbol: {} with interval: {}", symbol, interval, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Generate multiple charts with different intervals for the same symbol
     *
     * @param symbol    The instrument symbol (e.g., "RELIANCE", "INFY")
     * @param intervals Comma-separated list of intervals (e.g., "day,hour,15minute")
     * @return The first chart image as a response entity, all charts are saved to files
     */
    @GetMapping("/multi-interval")
    public ResponseEntity<byte[]> generateMultiIntervalCharts(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "day,hour,15minute") String intervals) {
        try {
            log.info("Generating multiple charts for symbol: {} with intervals: {}", symbol, intervals);

            // Parse intervals
            String[] intervalArray = intervals.split(",");
            List<IntervalBarSeries> barSeriesList = new ArrayList<>();

            // Get bar series data for each interval
            for (String interval : intervalArray) {
                IntervalBarSeries barSeries = barSeriesHelper.getIntervalBarSeries(symbol, interval.trim());
                if (barSeries != null && barSeries.getBarCount() > 0) {
                    barSeriesList.add(barSeries);
                } else {
                    log.warn("No data found for symbol: {} with interval: {}", symbol, interval);
                }
            }

            if (barSeriesList.isEmpty()) {
                log.warn("No data found for symbol: {} with any of the requested intervals", symbol);
                return ResponseEntity.notFound().build();
            }

            // Create chart configuration
            ChartConfig config = ChartConfig.builder()
                    .barSeries(barSeriesList)
                    .chartType(ChartType.CANDLESTICK)
                    .showVolume(true)
                    .showLegend(true)
                    .title(symbol + " - Multiple Intervals")
                    .build();

            // Generate charts - they will be saved with Symbol_Interval.png naming
            byte[] chartImageData = chartCreator.createChart(config);

            // Set response headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_PNG);
            headers.setContentLength(chartImageData.length);

            return new ResponseEntity<>(chartImageData, headers, HttpStatus.OK);

        } catch (Exception e) {
            log.error("Error generating multi-interval charts for symbol: {}", symbol, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Generate a multi-timeframe grid chart showing the same instrument across different timeframes
     *
     * @param symbol The instrument symbol (e.g., "RELIANCE", "INFY")
     * @return The grid chart image as a response entity
     */
    @GetMapping("/multi-timeframe")
    public ResponseEntity<byte[]> generateMultiTimeframeChart(@RequestParam String symbol) {
        try {
            log.info("Generating multi-timeframe grid chart for symbol: {}", symbol);

            // Define the timeframes we want to display (15min, 1h, 1d, 1w)
            // Use the exact kiteKey values from the Interval enum
            Interval[] timeframes = {Interval.FifteenMinute, Interval.OneHour, Interval.Day, Interval.Week};
            java.util.ArrayList<IntervalBarSeries> seriesList = new java.util.ArrayList<>();

            // Load data for each timeframe
            for (Interval timeframe : timeframes) {
                IntervalBarSeries barSeries = barSeriesHelper.getIntervalBarSeries(symbol, timeframe.name());

                if (barSeries != null && barSeries.getBarCount() > 0) {
                    // Set a title for each chart based on the timeframe
                    String title = symbol + " - " + timeframe.name();
                    // Add the series to our list
                    seriesList.add(barSeries);
                } else {
                    log.warn("No data found for symbol: {} with interval: {}", symbol, timeframe);
                }
            }

            if (seriesList.isEmpty()) {
                log.warn("No data found for any timeframe for symbol: {}", symbol);
                return ResponseEntity.notFound().build();
            }

            // Create grid configuration
            ChartGrid.GridConfig gridConfig = ChartGrid.GridConfig.builder()
                    .columns(2)  // 2x2 grid for 4 timeframes
                    .width(1024) // Larger width for better readability
                    .height(768) // Larger height for better readability
                    .padding(20) // More padding between charts
                    .baseConfig(ChartConfig.builder()
                            .chartType(ChartType.CANDLESTICK)
                            .showVolume(true)
                            .showLegend(true)
                            // Display a reasonable number of bars for each timeframe
                            .displayedBars(300)
                            .build())
                    .useMatplotlib(true) // Use matplotlib for better rendering
                    .build();

            // Generate grid chart
            byte[] chartImageData = chartGrid.createChartGrid(seriesList, gridConfig);

            // Set response headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_PNG);
            headers.setContentLength(chartImageData.length);

            return new ResponseEntity<>(chartImageData, headers, HttpStatus.OK);

        } catch (Exception e) {
            log.error("Error generating multi-timeframe grid chart for symbol: {}", symbol, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Generate a test chart with sample data
     * This endpoint is useful for testing the chart generation without requiring real market data
     *
     * @return The chart image as a response entity
     */
    @GetMapping("/test")
    public ResponseEntity<byte[]> generateTestChart() {
        try {
            log.info("Generating test chart with sample data");

            // Create sample data
            BaseBarSeries series = new BaseBarSeries("Sample Data");

            // Add sample bars (last 30 days of data)
            LocalDateTime endTime = LocalDateTime.now();
            double open = 100.0;
            double close = 100.0;

            for (int i = 30; i >= 0; i--) {
                LocalDateTime barTime = endTime.minusDays(i);

                // Create some random price movements
                double range = open * 0.02; // 2% range
                double high = open + Math.random() * range;
                double low = open - Math.random() * range;

                // Randomly move the close price
                close = open + (Math.random() - 0.5) * range * 2;

                // Create a bar
                Bar bar = new BaseBar(
                        Duration.ofDays(1),
                        barTime.atZone(ZoneOffset.UTC),
                        BigDecimal.valueOf(open),
                        BigDecimal.valueOf(high),
                        BigDecimal.valueOf(low),
                        BigDecimal.valueOf(close),
                        BigDecimal.valueOf(Math.random() * 1000000) // Random volume
                );

                series.addBar(bar);

                // Next day's open is today's close
                open = close;
            }

            // Convert to IntervalBarSeries
            IntervalBarSeries barSeries = new ExtendedBarSeries(series, Interval.Day, SeriesType.EQUITY, "SAMPLE");

            // Create chart configuration
            List<IntervalBarSeries> barSeriesList = new ArrayList<>();
            barSeriesList.add(barSeries);

            ChartConfig config = ChartConfig.builder()
                    .barSeries(barSeriesList)
                    .chartType(ChartType.CANDLESTICK)
                    .showVolume(true)
                    .showLegend(true)
                    .title("Sample Chart - Test Data")
                    .build();

            // Generate chart
            byte[] chartImageData = chartCreator.createChart(config);

            // Set response headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_PNG);
            headers.setContentLength(chartImageData.length);

            return new ResponseEntity<>(chartImageData, headers, HttpStatus.OK);

        } catch (Exception e) {
            log.error("Error generating test chart", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Generate a test chart with sample data and save it to a file
     * This endpoint is useful for testing the chart file saving functionality
     *
     * @param filename Optional filename to save the chart (default: test_chart_yyyy-MM-dd_HH-mm-ss.png)
     * @return The chart image as a response entity and saves it to a file
     */
    @GetMapping("/test/save")
    public ResponseEntity<byte[]> generateAndSaveTestChart(
            @RequestParam(required = false) String filename) {
        try {
            log.info("Generating and saving test chart with sample data");

            // Create sample data
            BaseBarSeries series = new BaseBarSeries("Sample Data");

            // Add sample bars (last 30 days of data)
            LocalDateTime endTime = LocalDateTime.now();
            double open = 100.0;
            double close = 100.0;

            for (int i = 30; i >= 0; i--) {
                LocalDateTime barTime = endTime.minusDays(i);

                // Create some random price movements
                double range = open * 0.02; // 2% range
                double high = open + Math.random() * range;
                double low = open - Math.random() * range;

                // Randomly move the close price
                close = open + (Math.random() - 0.5) * range * 2;

                // Create a bar
                Bar bar = new BaseBar(
                        Duration.ofDays(1),
                        barTime.atZone(ZoneOffset.UTC),
                        BigDecimal.valueOf(open),
                        BigDecimal.valueOf(high),
                        BigDecimal.valueOf(low),
                        BigDecimal.valueOf(close),
                        BigDecimal.valueOf(Math.random() * 1000000) // Random volume
                );

                series.addBar(bar);

                // Next day's open is today's close
                open = close;
            }

            // Convert to IntervalBarSeries
            IntervalBarSeries barSeries = new ExtendedBarSeries(series, Interval.Day, SeriesType.EQUITY, "SAMPLE");

            // Create chart configuration
            List<IntervalBarSeries> barSeriesList = new ArrayList<>();
            barSeriesList.add(barSeries);

            ChartConfig config = ChartConfig.builder()
                    .barSeries(barSeriesList)
                    .chartType(ChartType.CANDLESTICK)
                    .showVolume(true)
                    .showLegend(true)
                    .title("Sample Chart - Test Data")
                    .build();

            // Generate default filename if not provided
            if (filename == null || filename.isEmpty()) {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
                filename = "test_chart_" + LocalDateTime.now().format(formatter) + ".png";
            }

            // Ensure filename has .png extension
            if (!filename.toLowerCase().endsWith(".png") && !filename.toLowerCase().endsWith(".jpg")) {
                filename += ".png";
            }

            // Generate chart and save to file
            byte[] chartImageData = chartCreator.createChart(config, filename);

            log.info("Test chart saved to file: {}", filename);

            // Set response headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_PNG);
            headers.setContentLength(chartImageData.length);

            return new ResponseEntity<>(chartImageData, headers, HttpStatus.OK);

        } catch (Exception e) {
            log.error("Error generating and saving test chart", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Analyze charts for a given symbol across multiple timeframes using GPT
     *
     * @param request The chart analysis request containing symbol, timeframes, and candle count
     * @return The analysis response with GPT analysis and chart URLs
     */
    @PostMapping("/v1/analyze")
    public ResponseEntity<ChartAnalysisResponse> analyzeCharts(
            @Valid @RequestBody ChartAnalysisRequest request) {

        try {
            log.info("Analyzing charts for symbol: {} with timeframes: {}",
                    request.getSymbol(), request.getTimeframes());

            // Delegate to the chart analysis service
            ChartAnalysisResponse response = chartAnalysisService.analyzeCharts(request);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error analyzing charts for symbol: {}", request.getSymbol(), e);

            ChartAnalysisResponse errorResponse = ChartAnalysisResponse.builder()
                    .analysis("Error analyzing charts: " + e.getMessage())
                    .build();

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Generate TradingView charts for multiple timeframes
     *
     * @param request The trading view chart request containing symbol and timeframes
     * @return Response entity containing the chart image data
     */
    @PostMapping("/tradingview")
    public ResponseEntity<TradingViewChartResponse> generateTradingViewCharts(
            @RequestBody TradingViewChartRequest request) {

        try {
            log.info("Generating TradingView charts for symbol: {} with timeframes: {}",
                    request.getSymbol(), request.getTimeframes());

            TradingViewChartResponse response = tradingViewChartService.generateTradingViewCharts(request);

            if (response.getErrorMessage() != null) {
                log.warn("Error generating TradingView charts: {}", response.getErrorMessage());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error generating TradingView charts", e);

            TradingViewChartResponse errorResponse = TradingViewChartResponse.builder()
                    .symbol(request.getSymbol())
                    .errorMessage("Error generating charts: " + e.getMessage())
                    .build();

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Generate TradingView multi-panel chart image
     *
     * @param symbol     The instrument symbol
     * @param layout     Optional layout configuration (default 2x2)
     * @param showVolume Whether to show volume indicators (default true)
     * @return Response entity containing the chart image data
     */
    @GetMapping("/tradingview/multipanel")
    public ResponseEntity<byte[]> generateTradingViewMultiPanel(
            @RequestParam String symbol,
            @RequestParam(required = false, defaultValue = "2x2") String layout,
            @RequestParam(required = false, defaultValue = "true") boolean showVolume) {

        try {
            log.info("Generating TradingView multi-panel chart for symbol: {}", symbol);

            // Define the timeframes to display
            List<Interval> timeframes = List.of(
                    Interval.FifteenMinute,
                    Interval.OneHour,
                    Interval.Day,
                    Interval.Week);

            TradingViewChartRequest request = TradingViewChartRequest.builder()
                    .symbol(symbol)
                    .timeframes(timeframes)
                    .layout(layout)
                    .showVolume(showVolume)
                    .title(symbol + " Multi-Timeframe Analysis")
                    .build();

            TradingViewChartResponse response = tradingViewChartService.generateTradingViewCharts(request);

            if (response.getErrorMessage() != null) {
                log.warn("Error generating TradingView chart: {}", response.getErrorMessage());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }

            // Get the chart image from the file
            if (response.getChartUrl() != null) {
                String filePath = response.getChartUrl().replace("/charts/", "");
                Path chartPath = Paths.get(chartsTempDirectory, filePath);

                if (Files.exists(chartPath)) {
                    byte[] imageData = Files.readAllBytes(chartPath);

                    // Set response headers
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.IMAGE_PNG);
                    headers.setContentLength(imageData.length);
                    headers.setCacheControl("max-age=86400");

                    return new ResponseEntity<>(imageData, headers, HttpStatus.OK);
                }
            }

            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (Exception e) {
            log.error("Error generating TradingView multi-panel chart", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Screen multiple symbols for ASTA signals using provided timeframe map.
     * Accepts a JSON body with symbols and timeframeMap (RIPPLE/WAVE/TIDE/SUPER_TIDE -> Interval).
     */
    @PostMapping("/v1/screen")
    public ResponseEntity<com.dtech.algo.controller.dto.ASTAScreenResponse> screenSymbols(
            @Valid @RequestBody com.dtech.algo.controller.dto.ASTAScreenRequest request) {

        try {
            com.dtech.algo.controller.dto.ASTAScreenResponse response = astaScreenService.screen(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error screening symbols", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}