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

Analyze the provided multi-timeframe OHLC + indicator context and identify the IMPORTANT support/resistance levels, trendlines, fibonacci zones, and Elliott Wave labels.

Focus on LEVELS THAT MATTER (multi-touch, structural pivots) — not every minor swing. Quality over quantity.

Return STRICTLY this JSON shape — NO markdown, NO commentary, NO extra keys:

{
  "trendlines": [
    {
      "id": "tl1",
      "anchor_t0": "<ISO-8601 UTC, e.g. 2025-02-05T03:45:00Z>",
      "anchor_p0": <number>,
      "anchor_t1": "<ISO-8601 UTC>",
      "anchor_p1": <number>,
      "role": "support" | "resistance",
      "primary_timeframe": "weekly" | "daily" | "hourly",
      "confidence": <0.0-1.0>,
      "rationale": "<150 chars max>"
    }
  ],
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
  "elliott_waves": [
    {
      "wave": "1" | "2" | "3" | "4" | "5" | "A" | "B" | "C",
      "anchor_t": "<ISO-8601 UTC>",
      "anchor_p": <number>,
      "rationale": "<150 chars>"
    }
  ],
  "fib_zones": [
    {
      "id": "f1",
      "from_t": "<ISO-8601 UTC>", "from_p": <number>,
      "to_t": "<ISO-8601 UTC>", "to_p": <number>,
      "rationale": "<150 chars>"
    }
  ],
  "summary": "<300 chars overall structural read>",
  "watch_trades": [
    {
      "id": "wt1",
      "direction": "LONG" | "SHORT",
      "trigger_condition": "<trigger in <50 chars, e.g. 'Break and hourly close above 213'>",
      "entry": <number>,
      "stop": <number>,
      "target": <number>,
      "rr": <number>,
      "confidence": <0.0-1.0>,
      "validity_until": "<ISO-8601 UTC>",
      "rationale": "<200 chars max>"
    }
  ]
}

Aim for: 5-8 trendlines, 4-6 horizontals, 0-1 confident Elliott labelings (empty if uncertain — don't force), 1-2 fib zones from the major recent swing.

WATCH_TRADES: Emit 0-3 high-conviction trade setups per symbol. Only include if confidence >= 0.6 and setup is well-defined (clear trigger, entry, stop, target). Do NOT emit every line crossing or every minor opportunity.

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

            // Parse response JSON (strip markdown if present)
            String jsonText = response.text().trim();
            if (jsonText.startsWith("```json")) {
                jsonText = jsonText.substring(7);
            }
            if (jsonText.startsWith("```")) {
                jsonText = jsonText.substring(3);
            }
            if (jsonText.endsWith("```")) {
                jsonText = jsonText.substring(0, jsonText.length() - 3);
            }
            jsonText = jsonText.trim();

            // Validate JSON
            JsonNode levelsJson = objectMapper.readTree(jsonText);

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
}
