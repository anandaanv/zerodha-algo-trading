package com.dtech.kitecon.service.ai;

import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.kitecon.service.ai.tools.PatternType;
import com.dtech.kitecon.service.ai.tools.ValidationInput;
import com.dtech.kitecon.service.ai.tools.ValidationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.conversations.Conversation;
import com.openai.models.conversations.items.ItemCreateParams;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * OpenAIProviderService - OpenAI implementation of AIProvider
 * Uses OpenAI SDK directly for better reliability and error handling
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OpenAIProviderService implements AIProvider {

    private final ObjectMapper objectMapper;

    @Value("${openai.key:}")
    private String apiKey;

    @Value("${openai.model:gpt-4o-mini}")
    private String model;

    @Value("${openai.baseUrl:https://api.openai.com/v1}")
    private String baseUrl;

    private OpenAIClient openAIClient;

    @PostConstruct
    public void init() {
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            openAIClient = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .maxRetries(3)
                .build();
            log.info("OpenAI client initialized with model: {}", model);
        } else {
            log.warn("OpenAI API key not configured");
        }
    }

    @Override
    public String getProviderName() {
        return "openai";
    }

    @Override
    public boolean isAvailable() {
        return openAIClient != null;
    }

    @Override
    public int getPriority() {
        return 1; // Highest priority (primary provider)
    }

    @Override
    public int getCostEstimate() {
        return 3; // Relatively low cost with gpt-4o-mini
    }

    @Override
    public ValidationResult analyzePattern(
        ValidationInput input,
        PatternType patternType,
        ValidationResult programmaticResult
    ) {
        try {
            if (openAIClient == null) {
                log.warn("OpenAI client not initialized");
                return programmaticResult != null ? programmaticResult :
                    ValidationResult.builder()
                        .isValid(false)
                        .confidence(0.0)
                        .reason("OpenAI client not available")
                        .build();
            }

            // Build prompt for AI analysis
            String prompt = buildAnalysisPrompt(input, patternType, programmaticResult);

            // Call OpenAI using SDK
            String response = callOpenAI(prompt);

            if (response.trim().isEmpty()) {
                log.warn("Empty response from OpenAI for pattern analysis");
                return programmaticResult != null ? programmaticResult :
                    ValidationResult.builder()
                        .isValid(false)
                        .confidence(0.0)
                        .reason("AI returned empty response")
                        .build();
            }

            // Parse AI response and enhance programmatic result
            return enhanceResultWithAI(programmaticResult, response);

        } catch (Exception e) {
            log.error("Error calling OpenAI for pattern analysis", e);

            // Return programmatic result if AI fails
            if (programmaticResult != null) {
                return programmaticResult;
            }

            return ValidationResult.builder()
                .isValid(false)
                .confidence(0.0)
                .reason("AI analysis failed: " + e.getMessage())
                .build();
        }
    }

    @Override
    public PatternType identifyPattern(String comment) {
        try {
            if (openAIClient == null) {
                log.warn("OpenAI client not initialized");
                return PatternType.UNKNOWN;
            }

            StringBuilder prompt = new StringBuilder();
            prompt.append("You are a pattern recognition specialist. Your task is to classify the trader's comment ");
            prompt.append("into ONE specific chart pattern category.\n\n");

            prompt.append("=== TRADER'S COMMENT ===\n");
            prompt.append(String.format("\"%s\"\n\n", comment));

            prompt.append("=== AVAILABLE PATTERN TYPES ===\n");
            prompt.append("1. TRENDLINE_BREAKOUT - Price breaking through support/resistance trendlines\n");
            prompt.append("2. ASCENDING_TRIANGLE - Higher lows with flat resistance\n");
            prompt.append("3. DESCENDING_TRIANGLE - Lower highs with flat support\n");
            prompt.append("4. SYMMETRICAL_TRIANGLE - Converging trendlines\n");
            prompt.append("5. SUPPORT_RESISTANCE - Horizontal levels of demand/supply\n");
            prompt.append("6. ELLIOTT_IMPULSE - 5-wave impulsive structure (Wave 1-5)\n");
            prompt.append("7. ELLIOTT_CORRECTIVE - 3-wave corrective structure (A-B-C, EDT, etc.)\n");
            prompt.append("8. FIBONACCI_RETRACEMENT - Pullbacks to Fib levels (38.2%, 50%, 61.8%)\n");
            prompt.append("9. HEAD_AND_SHOULDERS - Reversal pattern with 3 peaks\n");
            prompt.append("10. CHANNEL - Parallel support and resistance lines\n");
            prompt.append("11. HARMONIC - Geometric patterns (Gartley, Butterfly, Bat, etc.)\n");
            prompt.append("12. UNKNOWN - Cannot determine or doesn't match any category\n\n");

            prompt.append("=== CLASSIFICATION RULES ===\n");
            prompt.append("• If multiple patterns mentioned, pick the PRIMARY one\n");
            prompt.append("• Elliott Wave terms (EDT, Wave 3, Wave 5, etc.) → ELLIOTT_CORRECTIVE or ELLIOTT_IMPULSE\n");
            prompt.append("• Generic terms without specifics → UNKNOWN\n");
            prompt.append("• Look for keywords but understand context\n\n");

            prompt.append("OUTPUT REQUIREMENT:\n");
            prompt.append("Respond with EXACTLY ONE pattern type from the list above (e.g., ELLIOTT_CORRECTIVE).\n");
            prompt.append("No explanation, no punctuation. Just the pattern type name.");

            String response = callOpenAI(prompt.toString());

            if (response.trim().isEmpty()) {
                log.warn("Empty response from OpenAI for pattern identification");
                return PatternType.UNKNOWN;
            }

            // Parse response to PatternType
            String cleaned = response.trim().toUpperCase().replace(" ", "_");

            try {
                return PatternType.valueOf(cleaned);
            } catch (IllegalArgumentException e) {
                log.warn("Could not parse AI pattern type: {}", response);
                return PatternType.UNKNOWN;
            }

        } catch (Exception e) {
            log.error("Error identifying pattern with OpenAI", e);
            // Fallback to keyword-based identification
            return PatternType.fromComment(comment);
        }
    }

    @Override
    public String getPatternAdvice(ValidationInput input, PatternType patternType) {
        try {
            if (openAIClient == null) {
                log.warn("OpenAI client not initialized");
                return "Unable to provide AI advice - OpenAI not configured.";
            }

            StringBuilder prompt = new StringBuilder();
            prompt.append("You are a professional trading advisor. A trader has identified a pattern and needs ");
            prompt.append("actionable trading advice.\n\n");

            prompt.append("=== CONTEXT ===\n");
            prompt.append(String.format("Symbol: %s (%s timeframe)\n", input.getSymbol(), input.getTimeframe()));
            prompt.append(String.format("Pattern Identified: %s\n", patternType.getDisplayName()));
            prompt.append(String.format("Trader's Notes: \"%s\"\n\n", input.getUserComment()));

            if (input.getDrawings() != null && !input.getDrawings().isEmpty()) {
                prompt.append(String.format("Chart has %d technical drawings/annotations\n\n",
                    input.getDrawings().size()));
            }

            prompt.append("=== PROVIDE ACTIONABLE ADVICE ===\n");
            prompt.append("Format your response as:\n\n");
            prompt.append("✓/✗ PATTERN VALIDITY: [One sentence - is this pattern correctly identified?]\n\n");
            prompt.append("📊 KEY LEVELS:\n");
            prompt.append("• Entry: [Specific price or condition]\n");
            prompt.append("• Stop Loss: [Risk management level]\n");
            prompt.append("• Target: [Profit objective]\n\n");
            prompt.append("⚠ RISKS: [Primary risk factor to watch]\n\n");
            prompt.append("📈 STRATEGY: [2-3 sentence actionable trade plan]\n\n");
            prompt.append("Keep advice practical and specific. Be honest if the pattern is questionable.");

            return callOpenAI(prompt.toString());

        } catch (Exception e) {
            log.error("Error getting pattern advice from OpenAI", e);
            return "Unable to provide AI advice at this time.";
        }
    }

    /**
     * Technical chat analysis — REASON or VALIDATE mode
     */
    public String technicalChatAnalysis(
        String symbol, String timeframe,
        List<ValidationInput.Drawing> drawings,
        List<OhlcBarDTO> bars,
        String userMessage, String mode
    ) {
        try {
            if (openAIClient == null) {
                log.warn("OpenAI client not initialized");
                return "AI analysis unavailable — OpenAI not configured.";
            }

            String prompt = "REASON".equalsIgnoreCase(mode)
                ? buildReasonPrompt(symbol, timeframe, drawings, bars)
                : buildValidatePrompt(symbol, timeframe, drawings, bars, userMessage);

            return callOpenAI(prompt);
        } catch (Exception e) {
            log.error("Error in technicalChatAnalysis", e);
            return "Unable to provide AI analysis at this time.";
        }
    }

    private String buildReasonPrompt(
        String symbol, String timeframe,
        List<ValidationInput.Drawing> drawings,
        List<OhlcBarDTO> bars
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a senior technical analyst with 20+ years experience.\n");
        sb.append("A trader is viewing ").append(symbol).append(" on a ").append(timeframe)
          .append(" chart and has drawn:\n\n");

        sb.append("=== DRAWINGS ===\n");
        appendDrawings(sb, drawings);
        appendOhlcCsv(sb, bars);

        sb.append("\nReason about these drawings professionally. Address:\n");
        sb.append("1. What structure/pattern is being identified?\n");
        sb.append("2. Is the geometry valid against the price data?\n");
        sb.append("3. Current trading bias implied?\n");
        sb.append("4. Key price levels (targets, supports, invalidation)?\n");
        sb.append("5. Any missing elements the trader should consider?\n");
        sb.append("Use exact price numbers from the data.");

        return sb.toString();
    }

    private String buildValidatePrompt(
        String symbol, String timeframe,
        List<ValidationInput.Drawing> drawings,
        List<OhlcBarDTO> bars,
        String userMessage
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a senior technical analyst in a conversation with a trader.\n");
        sb.append("Trader's claim: \"").append(userMessage).append("\"\n\n");
        sb.append("Chart: ").append(symbol).append(" / ").append(timeframe).append("\n");
        sb.append("Drawings currently on the chart:\n");

        appendDrawings(sb, drawings);
        appendOhlcCsv(sb, bars);

        sb.append("\nRULES:\n");
        sb.append("- If the claim references a specific drawing or structure that is NOT in the drawings ");
        sb.append("list (e.g., claim mentions \"wave 3\" or \"the trendline\" but no Elliott Wave or ");
        sb.append("trendline is drawn), respond with a natural conversational question asking them ");
        sb.append("to clarify or draw it. Example: \"You mentioned the 3rd wave — I don't see any ");
        sb.append("wave count marked on the chart. Which waves are you counting? Could you draw ");
        sb.append("them so I can validate your analysis?\"\n");
        sb.append("- If all referenced drawings are present, validate the claim:\n");
        sb.append("  1. Does the geometry support it?\n");
        sb.append("  2. What does price data confirm/contradict?\n");
        sb.append("  3. Verdict: CONFIRMED / PARTIALLY CONFIRMED / REJECTED\n");
        sb.append("  4. Trading implication.\n");
        sb.append("Be objective. Disagree if warranted. Keep it conversational.");

        return sb.toString();
    }

    private void appendDrawings(StringBuilder sb, List<ValidationInput.Drawing> drawings) {
        if (drawings.isEmpty()) {
            sb.append("No drawings found on the chart.\n");
            return;
        }
        for (ValidationInput.Drawing d : drawings) {
            sb.append(DrawingDescriber.describe(d)).append("\n");
        }
    }

    private void appendOhlcCsv(StringBuilder sb, List<OhlcBarDTO> bars) {
        if (bars != null && !bars.isEmpty()) {
            int maxCandles = Math.min(bars.size(), 200);
            int startIdx = bars.size() - maxCandles;
            sb.append(String.format("\n=== PRICE DATA (last %d candles, CSV) ===\n", maxCandles));
            sb.append("datetime,open,high,low,close,volume\n");
            for (int i = startIdx; i < bars.size(); i++) {
                OhlcBarDTO bar = bars.get(i);
                sb.append(String.format("%s,%.2f,%.2f,%.2f,%.2f,%.0f\n",
                    DrawingDescriber.ts(bar.getTime()), bar.getOpen(), bar.getHigh(),
                    bar.getLow(), bar.getClose(), bar.getVolume()));
            }
        }
    }

    /**
     * Call OpenAI API using SDK with Conversations/Response API
     */
    private String callOpenAI(String prompt) {
        // Create a conversation
        Conversation conversation = openAIClient.conversations().create();

        // Build user message
        ResponseInputItem userMessage = ResponseInputItem.ofMessage(
            ResponseInputItem.Message.builder()
                .addInputTextContent(prompt)
                .role(ResponseInputItem.Message.Role.USER)
                .build()
        );

        // Create response with input
        ResponseCreateParams responseParams = ResponseCreateParams.builder()
            .conversation(conversation.id())
            .model(model)
            .input(ResponseCreateParams.Input.ofResponse(java.util.List.of(userMessage)))
            .build();

        Response response = openAIClient.responses().create(responseParams);

        // Extract text from response
        String responseText = response.output().stream()
            .filter(ResponseOutputItem::isMessage)
            .map(resItem -> resItem.asMessage().content().stream()
                .filter(ResponseOutputMessage.Content::isOutputText)
                .map(c -> c.asOutputText().text())
                .collect(Collectors.joining()))
            .collect(Collectors.joining());

        return responseText;
    }

    /**
     * Build analysis prompt for AI
     */
    private String buildAnalysisPrompt(
        ValidationInput input,
        PatternType patternType,
        ValidationResult programmaticResult
    ) {
        StringBuilder prompt = new StringBuilder();

        // Role and context
        prompt.append("You are a senior technical analyst with 20+ years of experience in price action analysis, ");
        prompt.append("Elliott Wave Theory, and classical chart patterns. Your reputation depends on accuracy and ");
        prompt.append("objectivity. Analyze this trader's chart annotation with professional rigor.\n\n");

        // Chart details
        prompt.append("=== CHART DETAILS ===\n");
        prompt.append(String.format("Symbol: %s\n", input.getSymbol()));
        prompt.append(String.format("Timeframe: %s\n", input.getTimeframe()));
        prompt.append(String.format("Pattern Claimed: %s\n", patternType.getDisplayName()));

        // Drawing coordinates
        if (input.getDrawings() != null && !input.getDrawings().isEmpty()) {
            prompt.append(String.format("Chart Drawings: %d element(s)\n", input.getDrawings().size()));
            for (int i = 0; i < input.getDrawings().size(); i++) {
                ValidationInput.Drawing d = input.getDrawings().get(i);
                prompt.append(String.format("  [%d] type=%s", i + 1, d.getType()));
                if (d.getProperties() != null && d.getProperties().getLabel() != null) {
                    prompt.append(String.format(" label=\"%s\"", d.getProperties().getLabel()));
                }
                if (d.getPoints() != null && !d.getPoints().isEmpty()) {
                    prompt.append(" points=");
                    for (ValidationInput.Point pt : d.getPoints()) {
                        prompt.append(String.format("[t=%d,p=%.2f]", pt.getTimestamp(), pt.getPrice()));
                    }
                }
                prompt.append("\n");
            }
        }
        prompt.append("\n");

        // Trader's analysis
        prompt.append("=== TRADER'S ANALYSIS ===\n");
        prompt.append(String.format("\"%s\"\n\n", input.getUserComment()));

        // OHLC candlestick data
        if (input.getPriceData() != null && !input.getPriceData().isEmpty()) {
            int maxCandles = Math.min(input.getPriceData().size(), 200);
            int startIdx = input.getPriceData().size() - maxCandles;
            prompt.append(String.format("=== PRICE DATA (last %d candles of %d total) ===\n",
                maxCandles, input.getPriceData().size()));
            prompt.append("timestamp,open,high,low,close,volume\n");
            for (int i = startIdx; i < input.getPriceData().size(); i++) {
                ValidationInput.OHLCData bar = input.getPriceData().get(i);
                prompt.append(String.format("%d,%.2f,%.2f,%.2f,%.2f,%d\n",
                    bar.getTimestamp(), bar.getOpen(), bar.getHigh(), bar.getLow(),
                    bar.getClose(), bar.getVolume() != null ? bar.getVolume() : 0));
            }
            prompt.append("\n");
        }

        // Programmatic validation results
        if (programmaticResult != null) {
            prompt.append("=== ALGORITHMIC VALIDATION ===\n");
            prompt.append(String.format("Verdict: %s\n", programmaticResult.isValid() ? "✓ VALID" : "✗ INVALID"));
            prompt.append(String.format("Confidence: %.1f%%\n", programmaticResult.getConfidence() * 100));
            prompt.append(String.format("Reasoning: %s\n", programmaticResult.getReason()));

            if (!programmaticResult.getViolations().isEmpty()) {
                prompt.append("\n⚠ Rule Violations Detected:\n");
                for (ValidationResult.RuleViolation violation : programmaticResult.getViolations()) {
                    String emoji = switch (violation.getSeverity()) {
                        case "error" -> "🔴";
                        case "warning" -> "🟡";
                        default -> "🔵";
                    };
                    prompt.append(String.format("  %s %s\n", emoji, violation.getRuleName()));
                    prompt.append(String.format("     └─ %s\n", violation.getDescription()));
                }
            }

            if (programmaticResult.getMetrics() != null && !programmaticResult.getMetrics().isEmpty()) {
                prompt.append("\n📊 Pattern Metrics:\n");
                programmaticResult.getMetrics().forEach((key, value) ->
                    prompt.append(String.format("  • %s: %s\n", key, value))
                );
            }

            prompt.append("\n");
        }

        // Instructions
        prompt.append("=== YOUR TASK ===\n");
        prompt.append("Provide a professional assessment in this EXACT format:\n\n");
        prompt.append("VERDICT: [CONFIRM / REJECT / NEEDS_CLARIFICATION]\n\n");
        prompt.append("CONFIDENCE: [0-100]%\n\n");
        prompt.append("REASONING:\n");
        prompt.append("- [Key point 1 supporting your verdict]\n");
        prompt.append("- [Key point 2]\n");
        prompt.append("- [Key point 3]\n\n");
        prompt.append("TRADING IMPLICATIONS:\n");
        prompt.append("• Bias: [Bullish/Bearish/Neutral]\n");
        prompt.append("• Key Levels: [Specific price levels to watch]\n");
        prompt.append("• Risk: [Invalidation points or concerns]\n\n");
        prompt.append("ALTERNATIVE PATTERNS (if applicable):\n");
        prompt.append("- [Other possible interpretations]\n\n");
        prompt.append("Be objective. Disagree with the trader if necessary. Focus on technical accuracy over validation.");

        return prompt.toString();
    }

    /**
     * Enhance programmatic result with AI insights
     */
    private ValidationResult enhanceResultWithAI(ValidationResult programmaticResult, String aiResponse) {
        if (programmaticResult == null) {
            // AI-only result (no programmatic validation available)
            return ValidationResult.builder()
                .isValid(false)
                .confidence(0.5)
                .reason("AI analysis only")
                .detailedFeedback(aiResponse)
                .build();
        }

        // Enhance existing result with AI insights
        String enhancedFeedback = programmaticResult.getDetailedFeedback() + "\n\n" +
            "AI Analysis:\n" + aiResponse;

        return ValidationResult.builder()
            .isValid(programmaticResult.isValid())
            .confidence(programmaticResult.getConfidence())
            .reason(programmaticResult.getReason())
            .detailedFeedback(enhancedFeedback)
            .suggestions(programmaticResult.getSuggestions())
            .violations(programmaticResult.getViolations())
            .metrics(programmaticResult.getMetrics())
            .alternatives(programmaticResult.getAlternatives())
            .tradingImplication(programmaticResult.getTradingImplication())
            .build();
    }
}
