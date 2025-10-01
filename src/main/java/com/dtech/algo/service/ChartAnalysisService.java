package com.dtech.algo.service;

import com.dtech.algo.controller.dto.ChartAnalysisRequest;
import com.dtech.algo.controller.dto.ChartAnalysisResponse;
import com.dtech.algo.controller.dto.TradingViewChartRequest;
import com.dtech.algo.controller.dto.TradingViewChartResponse;
import com.dtech.algo.series.Interval;
import com.dtech.kitecon.controller.BarSeriesHelper;
import com.dtech.kitecon.repository.IndexSymbolRepository;
import com.dtech.kitecon.service.DataFetchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Service for analyzing charts across multiple timeframes
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChartAnalysisService {

    private final TradingViewChartService tradingViewChartService;
    private final OpenAiConversationsService openAiConversationsService;
    private final DataFetchService dataFetchService;
    private final IndexSymbolRepository indexSymbolRepository;

    @Value("${matplotlib.temp.directory:/tmp}")
    private String tempDirectory;

    @Value("${charts.use.tradingview:true}")
    private boolean useTradingViewCharts;

    @Value("${charts.visibleBars.default:400}")
    private int defaultVisibleBars;

    @Value("${openai.use-conversations:true}")
    private boolean useConversations;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Analyzes charts for a given symbol across multiple timeframes
     *
     * @param request The chart analysis request
     * @return The chart analysis response
     */
    public ChartAnalysisResponse analyzeCharts(ChartAnalysisRequest request) {
        try {
            String symbol = request.getSymbol();
            Map<String, String> symbolAnalysisMap = new HashMap<>();
            Map<String, Object> symbolJsonAnalysisMap = new HashMap<>();

            if (indexSymbolRepository.existsByIndexName(symbol)) {
                List<String> stocks = indexSymbolRepository.findAllSymbolsByIndexName(symbol);
                for (String stock : stocks) {
                    ChartAnalysisResponse stockResponse = analyzeStock(request, stock);
                    symbolAnalysisMap.put(stock, stockResponse.getAnalysis());
                    symbolJsonAnalysisMap.put(stock, stockResponse.getJsonAnalysis());
                }
            } else {
                ChartAnalysisResponse stockResponse = analyzeStock(request, symbol);
                symbolAnalysisMap.put(symbol, stockResponse.getAnalysis());
                symbolJsonAnalysisMap.put(symbol, stockResponse.getJsonAnalysis());
            }

            return ChartAnalysisResponse.builder()
                    .analysis(symbolAnalysisMap.size() == 1 ? symbolAnalysisMap.values().iterator().next() : "Multiple symbols analyzed")
                    .jsonAnalysis(symbolJsonAnalysisMap.size() == 1 ? symbolJsonAnalysisMap.values().iterator().next() :
                            Map.of("message", "Multiple symbols analyzed", "symbolCount", symbolJsonAnalysisMap.size()))
                    .symbolAnalysis(symbolAnalysisMap)
                    .symbolJsonAnalysis(symbolJsonAnalysisMap)
                    .build();
        } catch (Exception e) {
            log.error("Error analyzing charts", e);
            return ChartAnalysisResponse.builder()
                    .analysis("Error analyzing charts: " + e.getMessage())
                    .symbolAnalysis(new HashMap<>())
                    .build();
        }
    }

    private ChartAnalysisResponse analyzeStock(ChartAnalysisRequest request, String symbol) throws IOException, InterruptedException {
        List<Interval> timeframes = request.getTimeframes();
        int candleCount = request.getCandleCount() > 0
                ? Math.min(request.getCandleCount(), defaultVisibleBars)
                : defaultVisibleBars;

        // Download candle data for all timeframes
        timeframes.forEach(interval -> dataFetchService.downloadCandleData(symbol, interval, new String[]{"NSE"}));

        List<File> chartFiles = new ArrayList<>();

        // Create temp directory if it doesn't exist
        Path tempPath = Paths.get(tempDirectory);
        if (!Files.exists(tempPath)) {
            Files.createDirectories(tempPath);
        }

        // Use new TradingView chart service for comprehensive analysis
        return generateTradingViewAnalysis(request, symbol, timeframes, candleCount);

    }

    /**
     * Generate analysis using the new TradingView chart with all indicators
     */
    private ChartAnalysisResponse generateTradingViewAnalysis(ChartAnalysisRequest request, String symbol,
                                                              List<Interval> timeframes, int candleCount) throws IOException, InterruptedException {
        try {
            // Determine optimal layout based on number of timeframes
            String layout = determineOptimalLayout(timeframes.size());

            // Create TradingView chart request with all timeframes
            TradingViewChartRequest chartRequest = TradingViewChartRequest.builder()
                    .symbol(symbol)
                    .timeframes(timeframes) // Use all requested timeframes
                    .candleCount(candleCount)
                    .layout(layout) // Dynamic layout based on timeframe count
                    .title(symbol + " - Multi-Timeframe Technical Analysis")
                    .showVolume(true)
                    .build();

            // Generate the comprehensive TradingView chart
            TradingViewChartResponse chartResponse = tradingViewChartService.generateTradingViewCharts(chartRequest);

            if (chartResponse.getErrorMessage() != null) {
                return ChartAnalysisResponse.builder()
                        .analysis("Error generating TradingView chart: " + chartResponse.getErrorMessage())
                        .build();
            }

            // Extract the chart image file path
            String chartUrl = chartResponse.getChartUrl();
            if (chartUrl == null || chartUrl.isEmpty()) {
                return ChartAnalysisResponse.builder()
                        .analysis("Failed to generate chart image for analysis.")
                        .build();
            }

            // Convert URL to file path
            String fileName = chartUrl.substring(chartUrl.lastIndexOf("/") + 1);
            String filePath = tradingViewChartService.getChartsTempDirectory() + "/" + fileName;
            File chartFile = new File(filePath);

            if (!chartFile.exists()) {
                return ChartAnalysisResponse.builder()
                        .analysis("Chart file not found for analysis: " + filePath)
                        .build();
            }

            // Create enhanced prompt for comprehensive multi-timeframe technical analysis
            String enhancedPrompt = createMultiTimeframeTechnicalAnalysisPrompt(symbol, timeframes, candleCount);

            // Call OpenAI for analysis with the comprehensive chart
            String analysisText = openAiConversationsService.analyzeCharts(Arrays.asList(chartFile), symbol, Interval.OneHour); //FIXME This is hardcoded.

            // Try to parse JSON response
            Object jsonAnalysis = parseJsonResponse(analysisText);

            return ChartAnalysisResponse.builder()
                    .analysis(analysisText)
                    .jsonAnalysis(jsonAnalysis)
                    .build();

        } catch (Exception e) {
            log.error("Error generating TradingView chart analysis for symbol {}", symbol, e);
            return ChartAnalysisResponse.builder()
                    .analysis("Error generating TradingView chart analysis: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Determine optimal layout based on number of timeframes
     */
    private String determineOptimalLayout(int timeframeCount) {
        switch (timeframeCount) {
            case 1:
                return "1x1";
            case 2:
                return "1x2";
            case 3:
                return "2x2"; // 2x2 can accommodate 3 charts with one empty slot
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
                return "2x2"; // Default fallback
        }
    }


    /**
     * Create an enhanced prompt for technical analysis with all indicators (single timeframe - backward compatibility)
     */
    private String createTechnicalAnalysisPrompt(String symbol, Interval timeframe, int candleCount) {
        return createMultiTimeframeTechnicalAnalysisPrompt(symbol, Arrays.asList(timeframe), candleCount);
    }

    /**
     * Create an enhanced prompt for multi-timeframe technical analysis
     */
    private String createMultiTimeframeTechnicalAnalysisPrompt(String symbol, List<Interval> timeframes, int candleCount) {
        StringBuilder timeframeList = new StringBuilder();
        for (int i = 0; i < timeframes.size(); i++) {
            if (i > 0) timeframeList.append(", ");
            timeframeList.append(timeframes.get(i).name());
        }

        String basicPrompt = "Please provide a comprehensive multi-timeframe technical analysis of %s based on the chart grid. " +
                "RESPOND ONLY IN VALID JSON FORMAT with the following structure:\n\n";
        return basicPrompt;
//                String.format(
//                basicPrompt +
//                        "{\n" +
//                        "  \"symbol\": \"%s\",\n" +
//                        "  \"timeframes\": \"%s\",\n" +
//                        "  \"analysisDate\": \"ISO_DATE_STRING\",\n" +
//                        "  \"overallSentiment\": \"BULLISH|BEARISH|NEUTRAL\",\n" +
//                        "  \"confidenceLevel\": \"HIGH|MEDIUM|LOW\",\n" +
//                        "  \"trendAnalysis\": {\n" +
//                        "    \"shortTerm\": \"trend_direction_and_strength\",\n" +
//                        "    \"mediumTerm\": \"trend_direction_and_strength\",\n" +
//                        "    \"longTerm\": \"trend_direction_and_strength\",\n" +
//                        "    \"timeframeAlignment\": \"description_of_alignment_across_timeframes\"\n" +
//                        "  },\n" +
//                        "  \"technicalIndicators\": {\n" +
//                        "    \"ema\": {\n" +
//                        "      \"ema50\": \"signal_description\",\n" +
//                        "      \"ema100\": \"signal_description\",\n" +
//                        "      \"ema200\": \"signal_description\",\n" +
//                        "      \"alignment\": \"bullish|bearish|mixed\"\n" +
//                        "    },\n" +
//                        "    \"bollingerBands\": {\n" +
//                        "      \"position\": \"upper|middle|lower|squeeze\",\n" +
//                        "      \"volatility\": \"high|medium|low\",\n" +
//                        "      \"signal\": \"signal_interpretation\"\n" +
//                        "    },\n" +
//                        "    \"macd\": {\n" +
//                        "      \"signal\": \"bullish|bearish|neutral\",\n" +
//                        "      \"histogram\": \"increasing|decreasing|flat\",\n" +
//                        "      \"divergence\": \"bullish_divergence|bearish_divergence|none\"\n" +
//                        "    },\n" +
//                        "    \"rsi\": {\n" +
//                        "      \"value\": \"current_rsi_level_estimate\",\n" +
//                        "      \"condition\": \"overbought|oversold|neutral\",\n" +
//                        "      \"signal\": \"signal_interpretation\"\n" +
//                        "    },\n" +
//                        "    \"adx\": {\n" +
//                        "      \"strength\": \"strong|weak|moderate\",\n" +
//                        "      \"direction\": \"bullish|bearish|sideways\",\n" +
//                        "      \"diAnalysis\": \"analysis_of_di_plus_minus\"\n" +
//                        "    }\n" +
//                        "  },\n" +
//                        "  \"supportResistance\": {\n" +
//                        "    \"keySupport\": [\"level1\", \"level2\", \"level3\"],\n" +
//                        "    \"keyResistance\": [\"level1\", \"level2\", \"level3\"],\n" +
//                        "    \"confluenceLevels\": [\"level1\", \"level2\"]\n" +
//                        "  },\n" +
//                        "  \"tradingRecommendation\": {\n" +
//                        "    \"action\": \"BUY|SELL|HOLD|WAIT\",\n" +
//                        "    \"reasoning\": \"detailed_reasoning_for_recommendation\",\n" +
//                        "    \"entryZone\": {\n" +
//                        "      \"min\": \"entry_level_min\",\n" +
//                        "      \"max\": \"entry_level_max\"\n" +
//                        "    },\n" +
//                        "    \"stopLoss\": \"stop_loss_level\",\n" +
//                        "    \"targets\": {\n" +
//                        "      \"target1\": \"first_target_level\",\n" +
//                        "      \"target2\": \"second_target_level\",\n" +
//                        "      \"target3\": \"third_target_level\"\n" +
//                        "    },\n" +
//                        "    \"riskReward\": \"risk_reward_ratio\",\n" +
//                        "    \"positionSize\": \"recommended_position_sizing\",\n" +
//                        "    \"timeHorizon\": \"SHORT_TERM|MEDIUM_TERM|LONG_TERM\"\n" +
//                        "  },\n" +
//                        "  \"riskAssessment\": {\n" +
//                        "    \"riskLevel\": \"HIGH|MEDIUM|LOW\",\n" +
//                        "    \"volatility\": \"high|medium|low\",\n" +
//                        "    \"marketConditions\": \"trending|ranging|volatile\",\n" +
//                        "    \"warningSignals\": [\"warning1\", \"warning2\"]\n" +
//                        "  },\n" +
//                        "  \"keyLevelsToWatch\": {\n" +
//                        "    \"breakoutLevels\": [\"level1\", \"level2\"],\n" +
//                        "    \"reversalLevels\": [\"level1\", \"level2\"],\n" +
//                        "    \"criticalLevels\": [\"level1\", \"level2\"]\n" +
//                        "  },\n" +
//                        "  \"summary\": \"concise_summary_of_analysis_and_recommendation\"\n" +
//                        "}\n\n" +
//                        "IMPORTANT: Respond ONLY with valid JSON. Do not include any text before or after the JSON object. " +
//                        "Analyze the chart showing %s with timeframes %s (%d candles each) and all technical indicators visible.",
//            symbol, symbol, timeframeList.toString(), symbol, timeframeList.toString(), candleCount
//        );
    }

    /**
     * Parse JSON response from OpenAI, fallback to error object if parsing fails
     */
    private Object parseJsonResponse(String responseText) {
        try {
            // Try to extract JSON if response contains other text
            String jsonText = extractJsonFromText(responseText);
            if (jsonText != null) {
                JsonNode jsonNode = objectMapper.readTree(jsonText);
                return objectMapper.convertValue(jsonNode, Object.class);
            } else {
                // If no JSON found, try parsing the entire response
                JsonNode jsonNode = objectMapper.readTree(responseText);
                return objectMapper.convertValue(jsonNode, Object.class);
            }
        } catch (Exception e) {
            log.warn("Failed to parse JSON response from OpenAI: {}", e.getMessage());
            // Return fallback JSON structure
            Map<String, Object> fallbackJson = new HashMap<>();
            fallbackJson.put("error", "Failed to parse JSON response");
            fallbackJson.put("originalResponse", responseText);
            fallbackJson.put("parseError", e.getMessage());
            return fallbackJson;
        }
    }

    /**
     * Extract JSON object from text that might contain other content
     */
    private String extractJsonFromText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        // Look for JSON object boundaries
        int startIndex = text.indexOf('{');
        int endIndex = text.lastIndexOf('}');

        if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            return text.substring(startIndex, endIndex + 1);
        }

        return null;
    }
}