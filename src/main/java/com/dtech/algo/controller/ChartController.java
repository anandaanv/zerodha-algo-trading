package com.dtech.algo.controller;

import com.dtech.algo.controller.dto.ChartAnalysisRequest;
import com.dtech.algo.controller.dto.ChartAnalysisResponse;
import com.dtech.algo.controller.dto.TradingViewChartRequest;
import com.dtech.algo.controller.dto.TradingViewChartResponse;
import com.dtech.algo.series.Interval;
import com.dtech.algo.service.ChartAnalysisService;
import com.dtech.algo.service.TradingViewChartService;
import com.dtech.kitecon.controller.BarSeriesHelper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Controller for generating and serving chart images
 */
@Slf4j
@RestController
@RequestMapping("/api/charts")
@RequiredArgsConstructor
public class ChartController {

    private final BarSeriesHelper barSeriesHelper;
    private final ChartAnalysisService chartAnalysisService;
    private final TradingViewChartService tradingViewChartService;
    private final com.dtech.algo.service.ASTASignalService astaSignalService;
    private final com.dtech.algo.service.ASTAScreenService astaScreenService;

    @Value("${charts.temp.directory:./charts/temp}")
    private String chartsTempDirectory;
    
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