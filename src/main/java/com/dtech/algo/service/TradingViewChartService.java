package com.dtech.algo.service;

import com.dtech.algo.controller.dto.TradingViewChartRequest;
import com.dtech.algo.controller.dto.TradingViewChartResponse;
import com.dtech.algo.series.Interval;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.kitecon.controller.BarSeriesHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;
import org.ta4j.core.Bar;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;

import org.springframework.core.io.ClassPathResource;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.*;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.adx.MinusDIIndicator;
import org.ta4j.core.indicators.adx.PlusDIIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.Num;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service for generating and saving TradingView charts using Lightweight Charts
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class TradingViewChartService {

    private final BarSeriesHelper barSeriesHelper;

    @Value("${charts.output.directory:/tmp/charts}")
    private String chartsOutputDirectory;

    @Value("${charts.temp.directory:/tmp/charts/temp}")
    private String chartsTempDirectory;

    @Value("${charts.browser.pool.size:3}")
    private int browserPoolSize;

    @Value("${charts.browser.timeout:30}")
    private int browserTimeoutSeconds;

    /**
     * Get the charts temp directory path
     * @return The temp directory path
     */
    public String getChartsTempDirectory() {
        return chartsTempDirectory;
    }

    // Keep track of semaphores for each symbol to limit concurrent operations
    private final ConcurrentHashMap<String, Semaphore> symbolSemaphores = new ConcurrentHashMap<>();

    // Maximum number of concurrent chart renderings per symbol
    private static final int MAX_CONCURRENT_RENDERS_PER_SYMBOL = 2;

    // Browser pool management
    private BlockingQueue<BrowserInstance> browserPool;
    private final AtomicBoolean browserPoolInitialized = new AtomicBoolean(false);

    /**
     * Browser instance wrapper
     */
    private static class BrowserInstance {
        private final Process process;
        private final String debuggingPort;
        private final long createdTime;
        private volatile boolean inUse;

        public BrowserInstance(Process process, String debuggingPort) {
            this.process = process;
            this.debuggingPort = debuggingPort;
            this.createdTime = System.currentTimeMillis();
            this.inUse = false;
        }

        public Process getProcess() { return process; }
        public String getDebuggingPort() { return debuggingPort; }
        public long getCreatedTime() { return createdTime; }
        public boolean isInUse() { return inUse; }
        public void setInUse(boolean inUse) { this.inUse = inUse; }

        public boolean isAlive() {
            return process.isAlive();
        }

        public void destroy() {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * Initialize browser pool on service startup
     */
    @PostConstruct
    public void initializeBrowserPool() {
        if (browserPoolInitialized.compareAndSet(false, true)) {
            log.info("Initializing browser pool with {} instances", browserPoolSize);
            browserPool = new LinkedBlockingQueue<>(browserPoolSize);

            // Create browser instances
            for (int i = 0; i < browserPoolSize; i++) {
                try {
                    BrowserInstance browser = createBrowserInstance(9222 + i);
                    if (browser != null) {
                        browserPool.offer(browser);
                        log.info("Created browser instance {} with debugging port {}", i + 1, browser.getDebuggingPort());
                    }
                } catch (Exception e) {
                    log.error("Failed to create browser instance {}: {}", i + 1, e.getMessage(), e);
                }
            }

            log.info("Browser pool initialized with {} active instances", browserPool.size());

            // Start background thread to maintain browser health
            startBrowserHealthMonitor();
        }
    }

    /**
     * Cleanup browser pool on service shutdown
     */
    @PreDestroy
    public void destroyBrowserPool() {
        if (browserPoolInitialized.get()) {
            log.info("Shutting down browser pool");

            // Destroy all browser instances
            while (!browserPool.isEmpty()) {
                BrowserInstance browser = browserPool.poll();
                if (browser != null) {
                    browser.destroy();
                    log.debug("Destroyed browser instance with port {}", browser.getDebuggingPort());
                }
            }

            browserPoolInitialized.set(false);
            log.info("Browser pool shutdown complete");
        }
    }

    /**
     * Create a new browser instance
     */
    private BrowserInstance createBrowserInstance(int debuggingPort) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "google-chrome",
                    "--headless",
                    "--disable-gpu",
                    "--disable-software-rasterizer",
                    "--disable-background-timer-throttling",
                    "--disable-backgrounding-occluded-windows",
                    "--disable-renderer-backgrounding",
                    "--disable-features=TranslateUI",
                    "--disable-extensions",
                    "--disable-default-apps",
                    "--no-sandbox",
                    "--disable-dev-shm-usage",
                    "--remote-debugging-port=" + debuggingPort,
                    "--window-size=4000,2800",
                    "--default-background-color=00000000"
            );

            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            // Give browser time to start up
            Thread.sleep(2000);

            if (process.isAlive()) {
                return new BrowserInstance(process, String.valueOf(debuggingPort));
            } else {
                log.error("Browser process died immediately after startup");
                return null;
            }
        } catch (Exception e) {
            log.error("Failed to create browser instance: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Get an available browser instance from the pool
     */
    private BrowserInstance borrowBrowserInstance() throws InterruptedException {
        BrowserInstance browser = browserPool.poll(browserTimeoutSeconds, TimeUnit.SECONDS);
        if (browser != null) {
            browser.setInUse(true);

            // Check if browser is still alive
            if (!browser.isAlive()) {
                log.warn("Borrowed dead browser instance, creating replacement");
                browser = replaceBrowserInstance(browser);
            }
        } else {
            log.warn("No browser instances available in pool, creating temporary instance");
            browser = createBrowserInstance(9222 + (int)(Math.random() * 1000));
        }

        return browser;
    }

    /**
     * Return a browser instance to the pool
     */
    private void returnBrowserInstance(BrowserInstance browser) {
        if (browser != null) {
            browser.setInUse(false);

            if (browser.isAlive()) {
                // Return to pool if still alive
                if (!browserPool.offer(browser)) {
                    log.warn("Browser pool is full, destroying excess browser instance");
                    browser.destroy();
                }
            } else {
                log.warn("Returning dead browser instance, will be replaced");
                // Don't return dead browser to pool
                replaceBrowserInstanceAsync(browser);
            }
        }
    }

    /**
     * Replace a dead browser instance
     */
    private BrowserInstance replaceBrowserInstance(BrowserInstance deadBrowser) {
        String oldPort = deadBrowser.getDebuggingPort();
        deadBrowser.destroy();

        try {
            BrowserInstance newBrowser = createBrowserInstance(Integer.parseInt(oldPort));
            if (newBrowser != null) {
                log.info("Successfully replaced dead browser instance on port {}", oldPort);
                return newBrowser;
            }
        } catch (Exception e) {
            log.error("Failed to replace dead browser instance: {}", e.getMessage(), e);
        }

        return null;
    }

    /**
     * Asynchronously replace a dead browser instance
     */
    private void replaceBrowserInstanceAsync(BrowserInstance deadBrowser) {
        new Thread(() -> {
            BrowserInstance replacement = replaceBrowserInstance(deadBrowser);
            if (replacement != null) {
                browserPool.offer(replacement);
            }
        }, "browser-replacement-thread").start();
    }

    /**
     * Start background thread to monitor browser health
     */
    private void startBrowserHealthMonitor() {
        Thread healthMonitor = new Thread(() -> {
            while (browserPoolInitialized.get()) {
                try {
                    // Check browser health every 30 seconds
                    Thread.sleep(30000);

                    // Check if any browsers in pool are dead
                    int poolSize = browserPool.size();
                    for (int i = 0; i < poolSize; i++) {
                        BrowserInstance browser = browserPool.poll();
                        if (browser != null) {
                            if (browser.isAlive() && !browser.isInUse()) {
                                browserPool.offer(browser); // Return healthy browser
                            } else if (!browser.isAlive()) {
                                log.info("Detected dead browser instance, replacing...");
                                replaceBrowserInstanceAsync(browser);
                            }
                        }
                    }

                    // Ensure we maintain minimum pool size
                    while (browserPool.size() < browserPoolSize && browserPoolInitialized.get()) {
                        BrowserInstance newBrowser = createBrowserInstance(9222 + browserPool.size());
                        if (newBrowser != null) {
                            browserPool.offer(newBrowser);
                            log.info("Added new browser instance to maintain pool size");
                        } else {
                            break; // Failed to create, don't retry immediately
                        }
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Error in browser health monitor: {}", e.getMessage(), e);
                }
            }
        }, "browser-health-monitor");

        healthMonitor.setDaemon(true);
        healthMonitor.start();
    }

    /**
     * Generate TradingView charts for multiple timeframes
     * 
     * @param request The chart generation request
     * @return Response with chart information
     */
    public TradingViewChartResponse generateTradingViewCharts(TradingViewChartRequest request) {
        log.info("Generating TradingView charts for symbol: {} with {} timeframes", 
                request.getSymbol(), request.getTimeframes().size());

        String symbol = request.getSymbol();
        List<Interval> timeframes = request.getTimeframes();

        // Ensure directories exist
        createDirectories();

        // Get or create a semaphore for this symbol
        Semaphore symbolSemaphore = symbolSemaphores.computeIfAbsent(
                symbol, k -> new Semaphore(MAX_CONCURRENT_RENDERS_PER_SYMBOL));

        try {
            // Try to acquire a permit, with timeout
            if (!symbolSemaphore.tryAcquire(30, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("Timeout waiting for chart rendering permit for symbol: {}", symbol);
                return TradingViewChartResponse.builder()
                        .symbol(symbol)
                        .errorMessage("Timed out waiting for chart rendering resources")
                        .build();
            }

            try {
                // Load bar series for each timeframe
                List<IntervalBarSeries> seriesList = loadSeriesForTimeframes(symbol, timeframes);

                if (seriesList.isEmpty()) {
                    return TradingViewChartResponse.builder()
                            .symbol(symbol)
                            .errorMessage("No data available for the requested timeframes")
                            .build();
                }

                // Generate a unique filename for this chart
                String timestamp = java.time.LocalDateTime.now().format(
                        DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
                String filename = symbol + "_" + timestamp + ".png";
                String filePath = Paths.get(chartsTempDirectory, filename).toString();

                // Generate and save the chart
                byte[] chartImageData = generateMultiPanelChart(seriesList, request);
                Files.write(Paths.get(filePath), chartImageData);

                // Create the response
                List<TradingViewChartResponse.ChartPanelInfo> panels = createPanelInfoList(seriesList, request);

                return TradingViewChartResponse.builder()
                        .symbol(symbol)
                        .chartUrl(filePath)
                        .panels(panels)
                        .build();

            } finally {
                // Always release the semaphore
                symbolSemaphore.release();
            }
        } catch (Exception e) {
            log.error("Error generating TradingView charts for symbol: {}", symbol, e);
            return TradingViewChartResponse.builder()
                    .symbol(symbol)
                    .errorMessage("Error generating charts: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Create list of panel info objects for the response
     */
    private List<TradingViewChartResponse.ChartPanelInfo> createPanelInfoList(
            List<IntervalBarSeries> seriesList, TradingViewChartRequest request) {

        List<TradingViewChartResponse.ChartPanelInfo> panels = new ArrayList<>();

        // Parse layout dimensions
        String[] layoutDimensions = request.getLayout().split("x");
        int rows = Integer.parseInt(layoutDimensions[0]);
        int cols = Integer.parseInt(layoutDimensions[1]);

        for (int i = 0; i < seriesList.size(); i++) {
            IntervalBarSeries series = seriesList.get(i);
            int row = i / cols;
            int col = i % cols;

            panels.add(TradingViewChartResponse.ChartPanelInfo.builder()
                    .timeframe(series.getInterval().name())
                    .candleCount(series.getBarCount())
                    .position(row + "," + col)
                    .build());
        }

        return panels;
    }

    /**
     * Load bar series for each requested timeframe
     */
    private List<IntervalBarSeries> loadSeriesForTimeframes(String symbol, List<Interval> timeframes) {
        return timeframes.stream()
                .map(timeframe -> {
                    try {
                        IntervalBarSeries series = barSeriesHelper.getIntervalBarSeries(symbol, timeframe.name());
                        return series;
                    } catch (Exception e) {
                        log.error("Error loading bar series for {}, timeframe: {}", symbol, timeframe, e);
                        return null;
                    }
                })
                .filter(series -> series != null && series.getBarCount() > 0)
                .collect(Collectors.toList());
    }

    /**
     * Generate a multi-panel chart with the given series and request parameters
     * 
     * @param seriesList List of bar series to include in the chart
     * @param request The original chart request
     * @return Byte array containing the chart image data
     */
    public byte[] generateMultiPanelChart(List<IntervalBarSeries> seriesList, TradingViewChartRequest request) 
            throws IOException {

        try {
            // Create a unique ID for this chart rendering session
            String chartId = UUID.randomUUID().toString();
            String tempDir = chartsTempDirectory + "/" + chartId;
            Files.createDirectories(Paths.get(tempDir));

            // Generate chart data JSON for each series (limit to last N bars)
            int maxBars = Math.max(1, request.getCandleCount());
            List<String> chartDataJsons = generateChartDataJsons(seriesList, maxBars);

            // Create the HTML file with the charts (also trims/clips overlays to visible range)
            String htmlContent = generateMultiPanelHtml(chartDataJsons, request, seriesList, maxBars);
            String htmlFilePath = tempDir + "/chart.html";
            Files.write(Paths.get(htmlFilePath), htmlContent.getBytes(StandardCharsets.UTF_8));

            // Use Selenium, Playwright or another headless browser to render and capture screenshot
                byte[] screenshot = captureChartScreenshot(htmlFilePath);

            // Clean up temporary files
            deleteDirectory(Paths.get(tempDir));

            return screenshot;
        } catch (Exception e) {
            log.error("Error generating multi-panel chart", e);
            throw new IOException("Failed to generate chart: " + e.getMessage(), e);
        }
    }

    /**
     * Generate chart data JSONs for each series including indicators
     * Only the last maxBars entries are serialized to reduce payload size.
     */
    private List<String> generateChartDataJsons(List<IntervalBarSeries> seriesList, int maxBars) {
        return seriesList.stream()
                .map(series -> this.convertSeriesToJsonWithIndicators(series, maxBars))
                .collect(Collectors.toList());
    }

    /**
     * Data structure to hold all chart data including indicators
     */
    public static class ChartData {
        private final List<CandleData> candles;
        private final List<Double> ema50;
        private final List<Double> ema100;
        private final List<Double> ema200;
        private final List<Double> bollingerUpper;
        private final List<Double> bollingerMiddle;
        private final List<Double> bollingerLower;
        private final List<Double> macdLine;
        private final List<Double> macdSignal;
        private final List<Double> macdHistogram;
        private final List<Double> rsi;
        private final List<Double> adx;
        private final List<Double> plusDI;
        private final List<Double> minusDI;

        public ChartData(List<CandleData> candles, List<Double> ema50, List<Double> ema100,
                        List<Double> ema200, List<Double> bollingerUpper, List<Double> bollingerMiddle,
                        List<Double> bollingerLower, List<Double> macdLine, List<Double> macdSignal,
                        List<Double> macdHistogram, List<Double> rsi, List<Double> adx,
                        List<Double> plusDI, List<Double> minusDI) {
            this.candles = candles;
            this.ema50 = ema50;
            this.ema100 = ema100;
            this.ema200 = ema200;
            this.bollingerUpper = bollingerUpper;
            this.bollingerMiddle = bollingerMiddle;
            this.bollingerLower = bollingerLower;
            this.macdLine = macdLine;
            this.macdSignal = macdSignal;
            this.macdHistogram = macdHistogram;
            this.rsi = rsi;
            this.adx = adx;
            this.plusDI = plusDI;
            this.minusDI = minusDI;
        }

        // Getters
        public List<CandleData> getCandles() { return candles; }
        public List<Double> getEma50() { return ema50; }
        public List<Double> getEma100() { return ema100; }
        public List<Double> getEma200() { return ema200; }
        public List<Double> getBollingerUpper() { return bollingerUpper; }
        public List<Double> getBollingerMiddle() { return bollingerMiddle; }
        public List<Double> getBollingerLower() { return bollingerLower; }
        public List<Double> getMacdLine() { return macdLine; }
        public List<Double> getMacdSignal() { return macdSignal; }
        public List<Double> getMacdHistogram() { return macdHistogram; }
        public List<Double> getRsi() { return rsi; }
        public List<Double> getAdx() { return adx; }
        public List<Double> getPlusDI() { return plusDI; }
        public List<Double> getMinusDI() { return minusDI; }
    }

    public static class CandleData {
        private final long time;
        private final double open;
        private final double high;
        private final double low;
        private final double close;
        private final double volume;

        public CandleData(long time, double open, double high, double low, double close, double volume) {
            this.time = time;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volume = volume;
        }

        // Getters
        public long getTime() { return time; }
        public double getOpen() { return open; }
        public double getHigh() { return high; }
        public double getLow() { return low; }
        public double getClose() { return close; }
        public double getVolume() { return volume; }
    }

    /**
     * Convert a bar series to JSON format with all indicators calculated,
     * trimming to the last maxBars elements to limit payload size.
     */
    private String convertSeriesToJsonWithIndicators(IntervalBarSeries series, int maxBars) {
        // Calculate all indicators (full series)
        ChartData chartData = calculateIndicators(series);

        int total = chartData.getCandles().size();
        if (total == 0) {
            return "{\"candles\":[]}";
        }
        int fromIdx = Math.max(0, total - Math.max(1, maxBars));
        int toIdx = total; // exclusive

        // Trim candles and all indicator arrays to the last maxBars entries
        List<CandleData> candles = chartData.getCandles().subList(fromIdx, toIdx);
        List<Double> ema50      = chartData.getEma50().subList(fromIdx, toIdx);
        List<Double> ema100     = chartData.getEma100().subList(fromIdx, toIdx);
        List<Double> ema200     = chartData.getEma200().subList(fromIdx, toIdx);
        List<Double> bbUpper    = chartData.getBollingerUpper().subList(fromIdx, toIdx);
        List<Double> bbMiddle   = chartData.getBollingerMiddle().subList(fromIdx, toIdx);
        List<Double> bbLower    = chartData.getBollingerLower().subList(fromIdx, toIdx);
        List<Double> macdLine   = chartData.getMacdLine().subList(fromIdx, toIdx);
        List<Double> macdSignal = chartData.getMacdSignal().subList(fromIdx, toIdx);
        List<Double> macdHist   = chartData.getMacdHistogram().subList(fromIdx, toIdx);
        List<Double> rsi        = chartData.getRsi().subList(fromIdx, toIdx);
        List<Double> adx        = chartData.getAdx().subList(fromIdx, toIdx);
        List<Double> plusDI     = chartData.getPlusDI().subList(fromIdx, toIdx);
        List<Double> minusDI    = chartData.getMinusDI().subList(fromIdx, toIdx);

        StringBuilder json = new StringBuilder("{");

        // Add candle data
        json.append("\"candles\":[");
        for (int i = 0; i < candles.size(); i++) {
            if (i > 0) json.append(",");
            CandleData candle = candles.get(i);
            json.append("{")
                .append("\"time\":").append(candle.getTime()).append(",")
                .append("\"open\":").append(candle.getOpen()).append(",")
                .append("\"high\":").append(candle.getHigh()).append(",")
                .append("\"low\":").append(candle.getLow()).append(",")
                .append("\"close\":").append(candle.getClose()).append(",")
                .append("\"volume\":").append(candle.getVolume())
                .append("}");
        }
        json.append("],");

        // Add EMA data
        json.append("\"ema50\":").append(convertDoubleListToJson(ema50)).append(",");
        json.append("\"ema100\":").append(convertDoubleListToJson(ema100)).append(",");
        json.append("\"ema200\":").append(convertDoubleListToJson(ema200)).append(",");

        // Add Bollinger Bands
        json.append("\"bollingerUpper\":").append(convertDoubleListToJson(bbUpper)).append(",");
        json.append("\"bollingerMiddle\":").append(convertDoubleListToJson(bbMiddle)).append(",");
        json.append("\"bollingerLower\":").append(convertDoubleListToJson(bbLower)).append(",");

        // Add MACD
        json.append("\"macdLine\":").append(convertDoubleListToJson(macdLine)).append(",");
        json.append("\"macdSignal\":").append(convertDoubleListToJson(macdSignal)).append(",");
        json.append("\"macdHistogram\":").append(convertDoubleListToJson(macdHist)).append(",");

        // Add RSI
        json.append("\"rsi\":").append(convertDoubleListToJson(rsi)).append(",");

        // Add ADX and DI
        json.append("\"adx\":").append(convertDoubleListToJson(adx)).append(",");
        json.append("\"plusDI\":").append(convertDoubleListToJson(plusDI)).append(",");
        json.append("\"minusDI\":").append(convertDoubleListToJson(minusDI));

        json.append("}");
        return json.toString();
    }

    /**
     * Calculate all technical indicators for a bar series
     */
    private ChartData calculateIndicators(IntervalBarSeries series) {
        BarSeries barSeries = series; // IntervalBarSeries extends BarSeries
        ClosePriceIndicator closePrice = new ClosePriceIndicator(barSeries);

        List<CandleData> candles = new ArrayList<>();
        List<Double> ema50Values = new ArrayList<>();
        List<Double> ema100Values = new ArrayList<>();
        List<Double> ema200Values = new ArrayList<>();
        List<Double> bollingerUpperValues = new ArrayList<>();
        List<Double> bollingerMiddleValues = new ArrayList<>();
        List<Double> bollingerLowerValues = new ArrayList<>();
        List<Double> macdLineValues = new ArrayList<>();
        List<Double> macdSignalValues = new ArrayList<>();
        List<Double> macdHistogramValues = new ArrayList<>();
        List<Double> rsiValues = new ArrayList<>();
        List<Double> adxValues = new ArrayList<>();
        List<Double> plusDIValues = new ArrayList<>();
        List<Double> minusDIValues = new ArrayList<>();

        // Initialize indicators
        EMAIndicator ema50 = new EMAIndicator(closePrice, 50);
        EMAIndicator ema100 = new EMAIndicator(closePrice, 100);
        EMAIndicator ema200 = new EMAIndicator(closePrice, 200);

        // Bollinger Bands (20, 2.0)
        SMAIndicator sma20 = new SMAIndicator(closePrice, 20);
        StandardDeviationIndicator stdDev20 = new StandardDeviationIndicator(closePrice, 20);
        BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(sma20);
        DecimalNum two = DecimalNum.valueOf(2);
        BollingerBandsUpperIndicator bbUpper = new BollingerBandsUpperIndicator(bbMiddle, stdDev20, two);
        BollingerBandsLowerIndicator bbLower = new BollingerBandsLowerIndicator(bbMiddle, stdDev20, two);

        // MACD (12, 26, 9)
        EMAIndicator ema12 = new EMAIndicator(closePrice, 12);
        EMAIndicator ema26 = new EMAIndicator(closePrice, 26);
        MACDIndicator macd = new MACDIndicator(closePrice, 12, 26);
        EMAIndicator macdSignal = new EMAIndicator(macd, 9);

        // RSI (14)
        RSIIndicator rsi = new RSIIndicator(closePrice, 14);

        // ADX and DI (14)
        ADXIndicator adx = new ADXIndicator(barSeries, 14);
        PlusDIIndicator plusDI = new PlusDIIndicator(barSeries, 14);
        MinusDIIndicator minusDI = new MinusDIIndicator(barSeries, 14);

        // Calculate values for all bars
        for (int i = 0; i < barSeries.getBarCount(); i++) {
            Bar bar = barSeries.getBar(i);
            long timestamp = bar.getEndTime().toEpochSecond();

            // Candle data
            candles.add(new CandleData(
                timestamp,
                bar.getOpenPrice().doubleValue(),
                bar.getHighPrice().doubleValue(),
                bar.getLowPrice().doubleValue(),
                bar.getClosePrice().doubleValue(),
                bar.getVolume().doubleValue()
            ));

            // EMA values
            ema50Values.add(ema50.getValue(i).doubleValue());
            ema100Values.add(ema100.getValue(i).doubleValue());
            ema200Values.add(ema200.getValue(i).doubleValue());

            // Bollinger Bands
            bollingerUpperValues.add(bbUpper.getValue(i).doubleValue());
            bollingerMiddleValues.add(bbMiddle.getValue(i).doubleValue());
            bollingerLowerValues.add(bbLower.getValue(i).doubleValue());

            // MACD
            double macdValue = macd.getValue(i).doubleValue();
            double signalValue = macdSignal.getValue(i).doubleValue();
            macdLineValues.add(macdValue);
            macdSignalValues.add(signalValue);
            macdHistogramValues.add(macdValue - signalValue);

            // RSI
            rsiValues.add(rsi.getValue(i).doubleValue());

            // ADX and DI
            adxValues.add(adx.getValue(i).doubleValue());
            plusDIValues.add(plusDI.getValue(i).doubleValue());
            minusDIValues.add(minusDI.getValue(i).doubleValue());
        }

        return new ChartData(candles, ema50Values, ema100Values, ema200Values,
                bollingerUpperValues, bollingerMiddleValues, bollingerLowerValues,
                macdLineValues, macdSignalValues, macdHistogramValues,
                rsiValues, adxValues, plusDIValues, minusDIValues);
    }

    /**
     * Convert a list of Double values to JSON array format
     */
    private String convertDoubleListToJson(List<Double> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) json.append(",");
            Double value = values.get(i);
            if (value != null && !value.isNaN() && !value.isInfinite()) {
                json.append(value);
            } else {
                json.append("null");
            }
        }
        json.append("]");
        return json.toString();
    }

    /**
     * Generate HTML content for a multi-panel chart.
     * This trims bars to the last maxBars and clips/drops trendlines to the visible time range per panel.
     */
    private String generateMultiPanelHtml(List<String> chartDataJsons,
                                          TradingViewChartRequest request,
                                          List<IntervalBarSeries> seriesList,
                                          int maxBars)
            throws IOException {
        // Load the HTML template from resources
        Resource templateResource = new ClassPathResource("templates/multipanel-chart-template.html");
        String template = FileCopyUtils.copyToString(new InputStreamReader(
                templateResource.getInputStream(), StandardCharsets.UTF_8));

        // Parse layout dimensions
        String[] layoutDimensions = request.getLayout().split("x");
        int rows = Integer.parseInt(layoutDimensions[0]);
        int cols = Integer.parseInt(layoutDimensions[1]);

        // Generate chart container divs
        StringBuilder chartContainers = new StringBuilder();
        StringBuilder chartInitScripts = new StringBuilder();

        for (int i = 0; i < chartDataJsons.size(); i++) {
            String timeframeName = request.getTimeframes().get(i).name();
            String containerId = "chart-container-" + i;

            // Create container div
            chartContainers.append("<div class=\"chart-cell\" ")
                    .append("style=\"grid-column: " + ((i % cols) + 1) + "; ")
                    .append("grid-row: " + ((i / cols) + 1) + ";\">");
            chartContainers.append("<div class=\"chart-title\">" + request.getSymbol() + " - " + timeframeName + "</div>");
            chartContainers.append("<div id=\"" + containerId + "\" class=\"chart-container\"></div>");
            chartContainers.append("</div>");

            // Create initialization script
            chartInitScripts.append("createChart('")
                    .append(containerId).append("', ")
                    .append(chartDataJsons.get(i)).append(", ")
                    .append(request.isShowVolume()).append(", ")
                    .append("'").append(timeframeName).append("'");
            chartInitScripts.append(");");
        }

        // Build trimmed overlays (by timeframe) limited to last maxBars time window of each series
        String overlaysJson = "{}";
        if (request.getOverlays() != null && !request.getOverlays().isEmpty()) {
            java.util.Map<String, TradingViewChartRequest.OverlayLevels> trimmedOverlays = new java.util.HashMap<>();

            for (int i = 0; i < seriesList.size() && i < request.getTimeframes().size(); i++) {
                String timeframeName = request.getTimeframes().get(i).name();
                TradingViewChartRequest.OverlayLevels ol = request.getOverlays().get(timeframeName);
                if (ol == null) {
                    continue;
                }

                BarSeries s = seriesList.get(i);
                if (s == null || s.getBarCount() == 0) {
                    continue;
                }
                int fromIdx = Math.max(0, s.getBarCount() - Math.max(1, maxBars));
                int endIdx = s.getEndIndex();
                long minTime = s.getBar(fromIdx).getEndTime().toEpochSecond();
                long maxTime = s.getBar(endIdx).getEndTime().toEpochSecond();

                java.util.List<TradingViewChartRequest.TrendLine> trimmedTls = new java.util.ArrayList<>();
                if (ol.getTrendlines() != null) {
                    for (TradingViewChartRequest.TrendLine tl : ol.getTrendlines()) {
                        if (tl == null || tl.getStartTs() == null || tl.getEndTs() == null
                                || tl.getStartPrice() == null || tl.getEndPrice() == null) {
                            continue;
                        }
                        long sTs = tl.getStartTs();
                        long eTs = tl.getEndTs();
                        double sP = tl.getStartPrice();
                        double eP = tl.getEndPrice();

                        boolean reversed = sTs > eTs;
                        long ts1 = reversed ? eTs : sTs;
                        long ts2 = reversed ? sTs : eTs;
                        double p1 = reversed ? eP : sP;
                        double p2 = reversed ? sP : eP;

                        // Drop if completely outside range
                        if (ts2 < minTime || ts1 > maxTime) {
                            continue;
                        }

                        // Clip to [minTime, maxTime]
                        long newTs1 = Math.max(ts1, minTime);
                        long newTs2 = Math.min(ts2, maxTime);
                        double newP1, newP2;
                        if (ts2 != ts1) {
                            newP1 = p1 + (p2 - p1) * ((double) (newTs1 - ts1) / (double) (ts2 - ts1));
                            newP2 = p1 + (p2 - p1) * ((double) (newTs2 - ts1) / (double) (ts2 - ts1));
                        } else {
                            newP1 = p1;
                            newP2 = p2;
                        }

                        TradingViewChartRequest.TrendLine clipped;
                        if (!reversed) {
                            clipped = TradingViewChartRequest.TrendLine.builder()
                                    .startTs(newTs1).startPrice(newP1)
                                    .endTs(newTs2).endPrice(newP2)
                                    .label(tl.getLabel()).color(tl.getColor())
                                    .build();
                        } else {
                            // Preserve original orientation
                            clipped = TradingViewChartRequest.TrendLine.builder()
                                    .startTs(newTs2).startPrice(newP2)
                                    .endTs(newTs1).endPrice(newP1)
                                    .label(tl.getLabel()).color(tl.getColor())
                                    .build();
                        }
                        trimmedTls.add(clipped);
                    }
                }

                TradingViewChartRequest.OverlayLevels trimmedOl = TradingViewChartRequest.OverlayLevels.builder()
                        .support(ol.getSupport())
                        .resistance(ol.getResistance())
                        .trendlines(trimmedTls)
                        .label(ol.getLabel())
                        .build();

                trimmedOverlays.put(timeframeName, trimmedOl);
            }

            if (!trimmedOverlays.isEmpty()) {
                overlaysJson = new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(trimmedOverlays);
            }
        }

        // Replace placeholders in the template
        String html = template
                .replace("{{CHART_TITLE}}", request.getTitle() != null ? request.getTitle() :
                        request.getSymbol() + " Multi-Timeframe Chart")
                .replace("{{CHART_GRID_ROWS}}", String.valueOf(rows))
                .replace("{{CHART_GRID_COLS}}", String.valueOf(cols))
                .replace("{{CHART_CONTAINERS}}", chartContainers.toString())
                .replace("{{CHART_INIT_SCRIPTS}}", chartInitScripts.toString())
                .replace("{{OVERLAYS_JSON}}", overlaysJson)
                .replace("{{VISIBLE_BARS}}", String.valueOf(Math.max(1, maxBars)));

        return html;
    }

    /**
     * Capture a screenshot of the rendered chart HTML using browser pool
     */
    private byte[] captureChartScreenshot(String htmlFilePath) throws IOException {
        // Ensure browser pool is initialized
        if (!browserPoolInitialized.get()) {
            initializeBrowserPool();
        }

        BrowserInstance browser = null;
        try {
            // Get browser instance from pool
            browser = borrowBrowserInstance();
            if (browser == null) {
                throw new IOException("No browser instances available");
            }

            log.debug("Using browser instance with port {} for screenshot", browser.getDebuggingPort());

            // Create temporary file to store the screenshot
            Path screenshotPath = Paths.get(htmlFilePath).getParent().resolve("screenshot.png");

            // Use Chrome DevTools Protocol to capture screenshot
            String fileUrl = "file://" + Paths.get(htmlFilePath).toAbsolutePath().toString();

            // Create a new Chrome process specifically for this screenshot
            // (using the running instance via DevTools would be more complex)
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "google-chrome",
                    "--headless",
                    "--disable-gpu",
                    "--disable-software-rasterizer",
                    "--disable-background-timer-throttling",
                    "--disable-backgrounding-occluded-windows",
                    "--disable-renderer-backgrounding",
                    "--disable-features=TranslateUI",
                    "--disable-extensions",
                    "--disable-default-apps",
                    "--no-sandbox",
                    "--disable-dev-shm-usage",
                    "--screenshot=" + screenshotPath,
                    "--window-size=4000,2800",
                    "--default-background-color=00000000",
                    "--virtual-time-budget=10000", // Allow 10 seconds for rendering
                    fileUrl
            );

            processBuilder.redirectErrorStream(true);
            Process screenshotProcess = processBuilder.start();

            // Wait for the screenshot process to complete
            boolean completed = screenshotProcess.waitFor(browserTimeoutSeconds, TimeUnit.SECONDS);
            if (!completed) {
                screenshotProcess.destroyForcibly();
                throw new IOException("Screenshot process timed out after " + browserTimeoutSeconds + " seconds");
            }

            int exitCode = screenshotProcess.exitValue();
            if (exitCode != 0) {
                // Read the output to log the error
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(screenshotProcess.getInputStream()));
                String line;
                StringBuilder output = new StringBuilder();
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
                log.error("Chrome screenshot process failed with exit code {}: {}", exitCode, output.toString());
                throw new IOException("Screenshot process failed with exit code " + exitCode);
            }

            // Read the screenshot file
            if (Files.exists(screenshotPath)) {
                byte[] imageData = Files.readAllBytes(screenshotPath);
                log.debug("Successfully captured screenshot: {} bytes", imageData.length);
                return imageData;
            } else {
                throw new IOException("Screenshot file was not created");
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Screenshot capture was interrupted", e);
        } catch (Exception e) {
            log.error("Error capturing chart screenshot using browser pool", e);

            // Fallback: generate a simple image as placeholder
            BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g2d = img.createGraphics();
            g2d.setColor(java.awt.Color.WHITE);
            g2d.fillRect(0, 0, 800, 600);
            g2d.setColor(java.awt.Color.BLACK);
            g2d.drawString("Chart rendering failed: " + e.getMessage(), 50, 50);
            g2d.dispose();

            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } finally {
            // Always return browser to pool
            if (browser != null) {
                returnBrowserInstance(browser);
            }
        }
    }

    /**
     * Create the output directories if they don't exist
     */
    private void createDirectories() {
        try {
            // Ensure base /tmp/charts directory exists
            Path chartsPath = Paths.get("/tmp/charts");
            if (!Files.exists(chartsPath)) {
                Files.createDirectories(chartsPath);
                log.info("Created base charts directory at {}", chartsPath);
            }

            // Create configured output and temp directories
            Files.createDirectories(Paths.get(chartsOutputDirectory));
            Files.createDirectories(Paths.get(chartsTempDirectory));

            // Set appropriate permissions to ensure writing is possible
            File outputDir = new File(chartsOutputDirectory);
            File tempDir = new File(chartsTempDirectory);
            outputDir.setWritable(true, false);
            tempDir.setWritable(true, false);

            log.debug("Chart directories created successfully: {} and {}", chartsOutputDirectory, chartsTempDirectory);
        } catch (IOException e) {
            log.error("Error creating chart directories: {}", e.getMessage(), e);
            // Try fallback to system temp directory if custom paths fail
            try {
                String systemTempDir = System.getProperty("java.io.tmpdir");
                chartsOutputDirectory = Paths.get(systemTempDir, "charts").toString();
                chartsTempDirectory = Paths.get(systemTempDir, "charts", "temp").toString();

                Files.createDirectories(Paths.get(chartsOutputDirectory));
                Files.createDirectories(Paths.get(chartsTempDirectory));

                log.warn("Using fallback system temp directory for charts: {}", chartsOutputDirectory);
            } catch (IOException ex) {
                log.error("Critical error: Failed to create chart directories even in system temp location", ex);
            }
        }
    }

    /**
     * Delete a directory and all its contents
     */
    private void deleteDirectory(Path directory) {
        try {
            if (Files.exists(directory)) {
                Files.walk(directory)
                        .sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        } catch (IOException e) {
            log.warn("Error deleting temporary directory: {}", directory, e);
        }
    }
}
