package com.dtech.aitrader.service;

import com.dtech.aitrader.data.AiLevels;
import com.dtech.aitrader.data.AiTraderSymbol;
import com.dtech.aitrader.repository.AiLevelsRepository;
import com.dtech.aitrader.repository.AiTraderSymbolRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class LevelsAgentService {
    private final AiLevelsPromptBuilder promptBuilderService;
    private final AiProviderResolver aiProviderResolver;
    private final LlmGateway llmGateway;
    private final AiLevelsRepository aiLevelsRepository;
    private final AiTraderSymbolRepository aiTraderSymbolRepository;
    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT = """
You are a senior chart analyst specializing in structural levels on Indian FNO equities.

Analyze the provided multi-timeframe OHLC + indicator context and identify high-conviction support/resistance levels.

Focus on LEVELS THAT MATTER: multi-touch, round-number, structural pivots. Quality over quantity.

Return STRICTLY this JSON shape — NO markdown, NO commentary, NO extra keys:

{
  "horizontal_levels": [
    {
      "id": "h1",
      "price": <number>,
      "role": "support" | "resistance",
      "primary_timeframe": "weekly" | "daily" | "hourly",
      "confidence": <0.0-1.0>,
      "rationale": "<150 chars>"
    }
  ],
  "summary": "<300 chars overall structural read>"
}

Aim for 5-10 high-conviction horizontal levels only. Omit trendlines, elliott waves, fibonacci zones, and watch trades.

Use ISO-8601 UTC timestamps with the trailing 'Z'. Use absolute INR prices.
""";

    /**
     * Runs AI levels agent for a symbol (uses current time).
     */
    public AiLevels runForSymbol(String symbol, Long userId) {
        return runForSymbol(symbol, userId, Optional.empty());
    }

    /**
     * Runs AI levels agent for a symbol with optional asOf timestamp.
     * @param symbol The symbol to analyze
     * @param userId The user ID
     * @param asOf If present, slice data to this instant; otherwise use current time
     * @return Generated AiLevels record
     */
    public AiLevels runForSymbol(String symbol, Long userId, Optional<Instant> asOf) {
        log.info("Running AI levels agent for symbol: {}, userId: {}, asOf: {}", symbol, userId, asOf);

        try {
            // Resolve user's active AI provider
            AiProviderResolver.ProviderConfig cfg = aiProviderResolver.resolveForUser(userId);

            // Build prompt (with asOf if provided)
            String prompt = promptBuilderService.buildLevelsPrompt(symbol, asOf);

            // Call LLM gateway
            LlmGateway.LlmResponse response = llmGateway.call(cfg, SYSTEM_PROMPT, prompt, 4096);

            // Parse response JSON — defensively handle chatty preambles + markdown fences.
            // Sonnet usually obeys "JSON only" but NVIDIA / Llama-tuned models often prepend
            // "Based on the analysis..." or wrap in ```json fences. Extract the outermost {...}.
            String jsonText = extractJsonObject(response.text());

            JsonNode levelsJson;
            try {
                levelsJson = objectMapper.readTree(jsonText);
            } catch (Exception parseErr) {
                log.error("Levels agent: failed to parse JSON from model response. First 400 chars of raw response: {}",
                        response.text().length() > 400 ? response.text().substring(0, 400) : response.text());
                throw parseErr;
            }

            // Compute generatedForDate
            LocalDate generatedForDate = asOf
                    .map(i -> i.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate())
                    .orElse(LocalDate.now());

            // Persist AiLevels (upsert pattern to handle same-day reruns)
            AiLevels aiLevels = aiLevelsRepository
                    .findBySymbolAndTimeframeAndGeneratedForDate(symbol, "OneHour", generatedForDate)
                    .orElse(AiLevels.builder()
                            .symbol(symbol)
                            .timeframe("OneHour")
                            .generatedForDate(generatedForDate)
                            .build());

            // Always update fields (whether new or existing)
            aiLevels.setGeneratedAt(LocalDateTime.now());
            aiLevels.setLevelsJson(objectMapper.writeValueAsString(levelsJson));
            aiLevels.setInputTokens(response.inputTokens());
            aiLevels.setOutputTokens(response.outputTokens());
            aiLevels.setCostUsd(response.costUsd());
            aiLevels.setModelUsed(response.modelUsed());

            aiLevels = aiLevelsRepository.save(aiLevels);
            log.info("Saved AiLevels: id={}, symbol={}, tokens_in={}, tokens_out={}, cost=${}",
                    aiLevels.getId(), aiLevels.getSymbol(), aiLevels.getInputTokens(),
                    aiLevels.getOutputTokens(), aiLevels.getCostUsd());

            // Update or create AiTraderSymbol
            AiTraderSymbol symbolConfig = aiTraderSymbolRepository.findById(symbol)
                    .orElse(AiTraderSymbol.builder()
                            .symbol(symbol)
                            .aiLevelsEnabled(true)
                            .patternAgentEnabled(false)
                            .build());
            symbolConfig.setLastLevelsRunAt(LocalDateTime.now());
            aiTraderSymbolRepository.save(symbolConfig);

            return aiLevels;

        } catch (Exception e) {
            log.error("Error running levels agent for {} (userId: {})", symbol, userId, e);
            throw new RuntimeException("Levels agent failed: " + e.getMessage(), e);
        }
    }

    /**
     * Extract the outermost JSON object {...} from a model response. Tolerates:
     *   - leading prose ("Based on the analysis: {...}")
     *   - ```json fenced blocks
     *   - trailing commentary
     * Falls back to the original (trimmed) text if no braces found.
     */
    static String extractJsonObject(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        // Strip code fences if present
        if (s.startsWith("```json")) s = s.substring(7).trim();
        else if (s.startsWith("```")) s = s.substring(3).trim();
        if (s.endsWith("```")) s = s.substring(0, s.length() - 3).trim();
        // Find outermost balanced braces
        int start = s.indexOf('{');
        if (start < 0) return s;
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return s.substring(start, i + 1);
            }
        }
        // unbalanced — return from first { onward, parser will error and we'll log raw
        return s.substring(start);
    }
}
