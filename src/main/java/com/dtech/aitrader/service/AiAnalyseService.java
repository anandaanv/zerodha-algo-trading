package com.dtech.aitrader.service;

import com.dtech.aitrader.data.WatchTrade;
import com.dtech.aitrader.repository.WatchTradeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Service to generate watch trades via Claude analysis.
 * Pulls recent bars, indicators, user drawings, and AI levels.
 * Calls LLM to generate high-conviction watch trades.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiAnalyseService {

    private final AiAnalysePromptBuilder promptBuilder;
    private final WatchTradeRepository watchTradeRepository;
    private final LlmGateway llmGateway;
    private final AiProviderResolver aiProviderResolver;
    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT = """
You are a trade-plan analyst specializing in technical analysis and risk management.
Your role is to analyze charts, identify high-conviction setups, and propose concrete watch trades.

Guidelines:
- Be conservative: only propose trades when technical confirmation is strong
- Reference user-drawn lines and AI-detected levels explicitly
- Propose 0-3 trades per analysis (don't force trades if setup is weak)
- Always calculate realistic risk:reward ratios
- Use technical confluence: user lines + AI levels + indicator alignment
- Consider liquidity and price level context

Output JSON strictly as specified — no markdown, no commentary.
""";

    /**
     * Generates watch trades for a given symbol using Claude analysis.
     *
     * @param symbol Stock symbol
     * @param tabId User's tab ID (for scoped drawing lookup)
     * @param layoutId Chart layout ID
     * @param timeframe Timeframe (e.g. "OneHour")
     * @param userId User ID
     * @return List of generated WatchTrade entities with status WATCHING
     * @throws IllegalStateException if daily limit exceeded
     */
    public List<WatchTrade> runForSymbol(String symbol, String tabId, Long layoutId, String timeframe, Long userId) {
        // 1. Cost guard: check daily limit
        int countToday = watchTradeRepository
            .findBySymbolAndGeneratedForDateAndSourceType(symbol, LocalDate.now(), "AI_ANALYSE")
            .size();

        int maxPerDay = 5; // Will be overridable via config soon
        if (countToday >= maxPerDay) {
            throw new IllegalStateException(
                "Daily AI Analyse limit (" + maxPerDay + ") reached for symbol " + symbol);
        }

        log.info("Starting AI Analyse for symbol={}, tabId={}, layoutId={}, timeframe={}, userId={}",
            symbol, tabId, layoutId, timeframe, userId);

        try {
            // 2. Resolve LLM provider for user
            AiProviderResolver.ProviderConfig cfg = aiProviderResolver.resolveForUser(userId);

            // 3. Build prompt
            String userPrompt = promptBuilder.buildAnalysePrompt(symbol, tabId, layoutId, timeframe);

            // 4. Call LLM
            var response = llmGateway.call(cfg, SYSTEM_PROMPT, userPrompt, 2048);

            // 5. Parse response
            String cleanJson = LevelsAgentService.extractJsonObject(response.text());
            JsonNode root = objectMapper.readTree(cleanJson);
            JsonNode watchTradesArray = root.get("watch_trades");

            if (watchTradesArray == null || !watchTradesArray.isArray()) {
                log.warn("No watch_trades array in response for symbol={}", symbol);
                return new ArrayList<>();
            }

            // 6. Build WatchTrade rows
            List<WatchTrade> trades = new ArrayList<>();
            LocalDateTime now = LocalDateTime.now();
            LocalDate today = LocalDate.now();

            for (JsonNode wtNode : watchTradesArray) {
                try {
                    WatchTrade wt = buildWatchTradeFromJson(wtNode, symbol, userId, now, today);
                    trades.add(wt);
                    log.debug("Built watch trade: symbol={}, direction={}, entry={}, confidence={}",
                        symbol, wt.getDirection(), wt.getEntry(), wt.getConfidence());
                } catch (Exception e) {
                    log.warn("Error parsing watch trade node for symbol={}", symbol, e);
                }
            }

            // 7. Save all trades
            if (!trades.isEmpty()) {
                trades = watchTradeRepository.saveAll(trades);
                log.info("Saved {} watch trades for symbol={}, model used={}",
                    trades.size(), symbol, cfg.model());
            }

            return trades;

        } catch (Exception e) {
            log.error("Error in AiAnalyseService.runForSymbol for symbol={}", symbol, e);
            throw new RuntimeException("AI Analyse failed for symbol " + symbol, e);
        }
    }


    /**
     * Builds a WatchTrade entity from the JSON response node.
     */
    private WatchTrade buildWatchTradeFromJson(JsonNode node, String symbol, Long userId,
                                                LocalDateTime generatedAt, LocalDate generatedForDate) {
        String direction = node.get("direction") != null
            ? node.get("direction").asText()
            : null;

        BigDecimal entry = node.get("entry") != null
            ? BigDecimal.valueOf(node.get("entry").asDouble())
            : null;

        BigDecimal sl = node.get("stop") != null
            ? BigDecimal.valueOf(node.get("stop").asDouble())
            : null;

        BigDecimal target = node.get("target") != null
            ? BigDecimal.valueOf(node.get("target").asDouble())
            : null;

        Double rr = node.get("rr") != null
            ? node.get("rr").asDouble()
            : null;

        Double confidence = node.get("confidence") != null
            ? node.get("confidence").asDouble()
            : null;

        String triggerType = node.get("trigger_type") != null
            ? node.get("trigger_type").asText()
            : null;

        String triggerSpecJson = node.get("trigger_spec") != null
            ? node.get("trigger_spec").toString()
            : null;

        String rationale = node.get("rationale") != null
            ? node.get("rationale").asText()
            : null;

        // Validity: parse ISO-8601 or default to now+24h
        LocalDateTime validityUntil = generatedAt.plus(24, ChronoUnit.HOURS);
        if (node.get("validity_until") != null) {
            try {
                String validityStr = node.get("validity_until").asText();
                // Parse ISO-8601: 2026-05-20T10:30:00Z
                validityUntil = LocalDateTime.parse(validityStr.replace("Z", "").split("\\+")[0]);
            } catch (Exception e) {
                log.warn("Error parsing validity_until, using default +24h", e);
            }
        }

        return WatchTrade.builder()
            .symbol(symbol)
            .sourceType("AI_ANALYSE")
            .generatedAt(generatedAt)
            .generatedForDate(generatedForDate)
            .direction(direction)
            .entry(entry)
            .sl(sl)
            .target(target)
            .rr(rr)
            .confidence(confidence)
            .triggerType(triggerType)
            .triggerSpecJson(triggerSpecJson)
            .rationale(rationale)
            .status("WATCHING")
            .validityUntil(validityUntil)
            .userId(userId)
            .build();
    }
}
