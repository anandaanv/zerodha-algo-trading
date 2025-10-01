package com.dtech.algo.service;

import com.dtech.algo.controller.dto.ChartAnalysisResponse;
import com.dtech.algo.controller.dto.OpenAIAnalysisRequest;
import com.dtech.algo.controller.dto.TradingViewChartRequest;
import com.dtech.algo.controller.dto.TradingViewChartResponse;
import com.dtech.algo.series.Interval;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * New service to run OpenAI-based screening/analysis using TradingView chart generation.
 * Prompts and related details are sourced from the incoming request (no hardcoded prompts).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OpenAIScreenService {

    private final TradingViewChartService tradingViewChartService;
    private final OpenAiClientService openAiClientService;

    @Value("${charts.visibleBars.default:400}")
    private int defaultVisibleBars;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Analyze a symbol using the provided mapping and prompt references.
     * The method will generate a TradingView chart and call OpenAI with the request-provided prompt.
     */
    public ChartAnalysisResponse analyze(OpenAIAnalysisRequest request) {
        try {
            String symbol = request.getSymbol();
            if (symbol == null || symbol.isBlank()) {
                return ChartAnalysisResponse.builder()
                        .analysis("Symbol is required")
                        .build();
            }

            String prompt = request.getEffectivePrompt();
            if (prompt == null || prompt.isBlank()) {
                return ChartAnalysisResponse.builder()
                        .analysis("Prompt is required (provide promptId or promptJson)")
                        .build();
            }

            // Choose a default single timeframe for the initial flow; can be enhanced to derive from mapping.
            List<Interval> timeframes = request.getMapping().values()
                    .stream().map(mapping ->
                            Interval.valueOf(mapping.interval()))
                    .toList();
            int candleCount = defaultVisibleBars;

            return generateTradingViewAnalysis(request, symbol, timeframes, candleCount);
        } catch (Exception e) {
            log.error("Error in OpenAIScreenService.analyze", e);
            return ChartAnalysisResponse.builder()
                    .analysis("Error running OpenAI screen analysis: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Generate a TradingView chart and call OpenAI using the prompt from the request.
     */
    private ChartAnalysisResponse generateTradingViewAnalysis(OpenAIAnalysisRequest request,
                                                             String symbol,
                                                             List<Interval> timeframes,
                                                             int candleCount) {
        try {
            String layout = determineOptimalLayout(timeframes.size());

            TradingViewChartRequest chartRequest = TradingViewChartRequest.builder()
                    .symbol(symbol)
                    .timeframes(timeframes)
                    .candleCount(candleCount)
                    .layout(layout)
                    .title(symbol + " - Technical Analysis")
                    .showVolume(true)
                    .build();

            TradingViewChartResponse chartResponse = tradingViewChartService.generateTradingViewCharts(chartRequest);
            if (chartResponse.getErrorMessage() != null) {
                return ChartAnalysisResponse.builder()
                        .analysis("Error generating TradingView chart: " + chartResponse.getErrorMessage())
                        .build();
            }

            String chartUrl = chartResponse.getChartUrl();
            if (chartUrl == null || chartUrl.isEmpty()) {
                return ChartAnalysisResponse.builder()
                        .analysis("Failed to generate chart image for analysis.")
                        .build();
            }

            // Convert URL to local file path used by TradingViewChartService
            String fileName = chartUrl.substring(chartUrl.lastIndexOf("/") + 1);
            String filePath = tradingViewChartService.getChartsTempDirectory() + "/" + fileName;
            File chartFile = new File(filePath);
            if (!chartFile.exists()) {
                return ChartAnalysisResponse.builder()
                        .analysis("Chart file not found for analysis: " + filePath)
                        .build();
            }

            // Use prompt details from request (no hardcoded prompt)
            String prompt = request.getEffectivePrompt();

            String analysisText;
            try {
                analysisText = openAiClientService.analyzeChartsWithPrompt(Arrays.asList(chartFile), symbol, prompt);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return ChartAnalysisResponse.builder()
                        .analysis("OpenAI analysis interrupted")
                        .build();
            }

            Object jsonAnalysis = parseJsonResponse(analysisText);

            return ChartAnalysisResponse.builder()
                    .analysis(analysisText)
                    .jsonAnalysis(jsonAnalysis)
                    .build();

        } catch (Exception e) {
            log.error("Error generating TradingView analysis via OpenAIScreenService for symbol {}", symbol, e);
            return ChartAnalysisResponse.builder()
                    .analysis("Error generating TradingView analysis: " + e.getMessage())
                    .build();
        }
    }

    private String determineOptimalLayout(int timeframeCount) {
        switch (timeframeCount) {
            case 1:
                return "1x1";
            case 2:
                return "1x2";
            case 3:
                return "2x2";
            case 4:
                return "2x2";
            case 5:
            case 6:
                return "2x3";
            case 7:
            case 8:
                return "2x4";
            case 9:
                return "3x3";
            default:
                return "2x2";
        }
    }

    private Object parseJsonResponse(String responseText) {
        try {
            String jsonText = extractJsonFromText(responseText);
            if (jsonText != null) {
                JsonNode jsonNode = objectMapper.readTree(jsonText);
                return objectMapper.convertValue(jsonNode, Object.class);
            } else {
                JsonNode jsonNode = objectMapper.readTree(responseText);
                return objectMapper.convertValue(jsonNode, Object.class);
            }
        } catch (Exception e) {
            log.warn("Failed to parse JSON response from OpenAI: {}", e.getMessage());
            return Map.of(
                    "error", "Failed to parse JSON response",
                    "originalResponse", responseText,
                    "parseError", e.getMessage()
            );
        }
    }

    private String extractJsonFromText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        int startIndex = text.indexOf('{');
        int endIndex = text.lastIndexOf('}');
        if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            return text.substring(startIndex, endIndex + 1);
        }
        return null;
    }
}
