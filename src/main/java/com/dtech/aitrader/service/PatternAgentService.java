package com.dtech.aitrader.service;

import com.dtech.aitrader.data.AgentDecision;
import com.dtech.aitrader.data.PaperTrade;
import com.dtech.aitrader.model.PatternSignal;
import com.dtech.aitrader.repository.AgentDecisionRepository;
import com.dtech.aitrader.repository.PaperTradeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * PatternAgentService orchestrates pattern signal evaluation via Claude.
 * Decides TRADE/NO_TRADE and optionally creates paper trades.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PatternAgentService {
    private final PatternAgentPromptBuilder promptBuilder;
    private final LlmGateway llmGateway;
    private final AiProviderResolver providerResolver;
    private final AgentDecisionRepository agentDecisionRepository;
    private final PaperTradeRepository paperTradeRepository;
    private final ObjectMapper objectMapper;

    @Value("${pattern.model:claude-sonnet-4-5-20250929}")
    private String patternModel;

    @Value("${pattern.enabled:false}")
    private boolean patternEnabled;

    @Value("${paper_trade.enabled:false}")
    private boolean paperTradeEnabled;

    /**
     * Decides whether to take a trade based on a pattern signal.
     *
     * @param signal The pattern signal
     * @param userId The user requesting the decision
     * @return AgentDecision with verdict, entry/SL/target, and cost
     */
    public AgentDecision decide(PatternSignal signal, Long userId) {
        log.info("PatternAgent.decide: symbol={}, patternType={}, direction={}",
            signal.getSymbol(), signal.getPatternType(), signal.getDirection());

        // Build prompt
        String userPrompt = promptBuilder.buildPatternPrompt(signal);

        // Resolve provider config (uses model from DB user config, not the @Value)
        AiProviderResolver.ProviderConfig cfg = providerResolver.resolveForUser(userId);

        // Override model if pattern.model is set and not default
        String modelToUse = patternModel;
        if (patternModel != null && !patternModel.isBlank() &&
            !patternModel.equals("claude-sonnet-4-5-20250929")) {
            modelToUse = patternModel;
        }

        // Build a custom config with the pattern model
        AiProviderResolver.ProviderConfig customCfg =
            new AiProviderResolver.ProviderConfig(cfg.apiKey(), modelToUse, cfg.baseUrl(), cfg.isAnthropic(), cfg.isLocal());

        // Call LLM with system prompt focused on single trade decision
        String systemPrompt = "You are a quant trader making a SINGLE binary decision on whether to take a trade. " +
            "A pattern engine has fired a signal. Filter aggressively for trend conflicts, poor R:R, extreme indicators, and weak price action. " +
            "Output STRICTLY JSON. No markdown.";

        LlmGateway.LlmResponse response = llmGateway.call(customCfg, systemPrompt, userPrompt, 500);

        // Parse Claude response
        PatternDecision decision = parseResponse(response.text());

        // Build AgentDecision row
        AgentDecision agentDecision = AgentDecision.builder()
            .userId(userId)
            .symbol(signal.getSymbol())
            .agentType("pattern")
            .patternSource(signal.getPatternSource())
            .patternSignalRef(signal.getSignalRef())
            .decidedAt(LocalDateTime.now())
            .verdict(decision.verdict)
            .direction(decision.direction)
            .entry(decision.entry != null ? new BigDecimal(decision.entry) : null)
            .sl(decision.stop != null ? new BigDecimal(decision.stop) : null)
            .target(decision.target != null ? new BigDecimal(decision.target) : null)
            .confidence(decision.confidence)
            .reasoning(decision.reasoning)
            .inputTokens(response.inputTokens())
            .outputTokens(response.outputTokens())
            .costUsd(response.costUsd())
            .modelUsed(response.modelUsed())
            .build();

        // Persist
        agentDecision = agentDecisionRepository.save(agentDecision);

        log.info("PatternAgent decision: symbol={}, verdict={}, direction={}, confidence={}, cost=${}",
            signal.getSymbol(), decision.verdict, decision.direction, decision.confidence, response.costUsd());

        // If TRADE and flags enabled, create paper trade
        if ("TRADE".equals(decision.verdict) && patternEnabled && paperTradeEnabled) {
            createPaperTrade(signal, decision, agentDecision.getId());
        }

        return agentDecision;
    }

    /**
     * Parses Claude's JSON response into a PatternDecision object.
     */
    private PatternDecision parseResponse(String responseText) {
        try {
            String cleanText = responseText.trim();

            // Strip markdown fences if present
            if (cleanText.startsWith("```json")) {
                cleanText = cleanText.substring(7);
            } else if (cleanText.startsWith("```")) {
                cleanText = cleanText.substring(3);
            }
            if (cleanText.endsWith("```")) {
                cleanText = cleanText.substring(0, cleanText.length() - 3);
            }
            cleanText = cleanText.trim();

            JsonNode node = objectMapper.readTree(cleanText);
            return new PatternDecision(
                node.path("verdict").asText("NO_TRADE"),
                node.path("direction").asText(null),
                node.path("entry").isNull() ? null : node.path("entry").asDouble(),
                node.path("stop").isNull() ? null : node.path("stop").asDouble(),
                node.path("target").isNull() ? null : node.path("target").asDouble(),
                node.path("confidence").asDouble(0.0),
                node.path("reasoning").asText("")
            );
        } catch (Exception e) {
            log.error("Failed to parse Claude response: {}", responseText, e);
            throw new RuntimeException("Failed to parse pattern agent response: " + e.getMessage(), e);
        }
    }

    /**
     * Creates a PaperTrade row from a TRADE decision.
     */
    private void createPaperTrade(PatternSignal signal, PatternDecision decision, Long agentDecisionId) {
        PaperTrade trade = PaperTrade.builder()
            .agentDecisionId(agentDecisionId)
            .symbol(signal.getSymbol())
            .direction(decision.direction)
            .entryPrice(new BigDecimal(decision.entry != null ? decision.entry : signal.getSuggestedEntry()))
            .sl(new BigDecimal(decision.stop != null ? decision.stop : signal.getSuggestedSl()))
            .target(new BigDecimal(decision.target != null ? decision.target : signal.getSuggestedTarget()))
            .openedAt(LocalDateTime.now())
            .status("OPEN")
            .createdAt(LocalDateTime.now())
            .build();

        paperTradeRepository.save(trade);
        log.info("Created paper trade: symbol={}, direction={}, entry={}, sl={}, target={}",
            signal.getSymbol(), decision.direction, trade.getEntryPrice(), trade.getSl(), trade.getTarget());
    }

    /**
     * Internal class to hold parsed Claude decision.
     */
    private static class PatternDecision {
        String verdict;         // "TRADE" | "NO_TRADE"
        String direction;       // "LONG" | "SHORT" | null
        Double entry;           // override entry or null
        Double stop;            // override SL or null
        Double target;          // override target or null
        Double confidence;      // 0.0-1.0
        String reasoning;       // explanation

        PatternDecision(String verdict, String direction, Double entry, Double stop, Double target, Double confidence, String reasoning) {
            this.verdict = verdict;
            this.direction = direction;
            this.entry = entry;
            this.stop = stop;
            this.target = target;
            this.confidence = confidence;
            this.reasoning = reasoning;
        }
    }
}
