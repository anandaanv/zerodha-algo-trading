package com.dtech.aitrader.service;

import com.dtech.aitrader.data.AgentDecision;
import com.dtech.aitrader.data.PaperTrade;
import com.dtech.aitrader.model.ExitDecision;
import com.dtech.aitrader.repository.AgentDecisionRepository;
import com.dtech.aitrader.repository.PaperTradeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ExitAgentService orchestrates exit decisions for open paper trades via Claude.
 * Decides HOLD / CLOSE_NOW / MOVE_TO_BREAKEVEN / TRAIL_TO_X / EXTEND_TARGET_TO_Y.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExitAgentService {
    private final ExitAgentPromptBuilder promptBuilder;
    private final LlmGateway llmGateway;
    private final AiProviderResolver providerResolver;
    private final AgentDecisionRepository agentDecisionRepository;
    private final PaperTradeRepository paperTradeRepository;
    private final ObjectMapper objectMapper;

    /**
     * Decides whether to hold, close, or adjust stops/targets for an open trade.
     *
     * @param trade The open paper trade
     * @param userId The user requesting the decision
     * @return ExitDecision with action, optional new stops/targets, and cost
     */
    public ExitDecision decide(PaperTrade trade, Long userId) {
        log.info("ExitAgent.decide: tradeId={}, symbol={}, direction={}",
            trade.getId(), trade.getSymbol(), trade.getDirection());

        // Build prompt
        String userPrompt = promptBuilder.build(trade, trade.getSymbol());

        // Resolve provider config
        AiProviderResolver.ProviderConfig cfg = providerResolver.resolveForUser(userId);

        // Call LLM with system prompt
        String systemPrompt = "You are an exit manager for a single open trade. The trader has fixed entry/SL/target. " +
            "Your job: every check, decide if we should hold, exit, or move the stop/target based on price action and structural context. " +
            "Output STRICT JSON only. No markdown.";

        LlmGateway.LlmResponse response = llmGateway.call(cfg, systemPrompt, userPrompt, 500);

        // Parse Claude response
        ExitDecisionParsed parsed = parseResponse(response.text());

        // Build and persist AgentDecision for audit
        AgentDecision agentDecision = AgentDecision.builder()
            .userId(userId)
            .symbol(trade.getSymbol())
            .agentType("exit")
            .patternSource("exit_manager")
            .patternSignalRef(String.valueOf(trade.getId()))
            .decidedAt(LocalDateTime.now())
            .verdict(parsed.action)
            .direction(trade.getDirection())
            .entry(trade.getEntryPrice())
            .sl(trade.getSl())
            .target(trade.getTarget())
            .confidence(parsed.confidence)
            .reasoning(parsed.reasoning)
            .inputTokens(response.inputTokens())
            .outputTokens(response.outputTokens())
            .costUsd(response.costUsd())
            .modelUsed(response.modelUsed())
            .build();

        agentDecisionRepository.save(agentDecision);

        log.info("ExitAgent decision: tradeId={}, action={}, confidence={}, cost=${}",
            trade.getId(), parsed.action, parsed.confidence, response.costUsd());

        // Return ExitDecision record
        return new ExitDecision(
            parsed.action,
            parsed.newStop,
            parsed.newTarget,
            parsed.confidence,
            parsed.reasoning,
            response.inputTokens(),
            response.outputTokens(),
            response.costUsd().doubleValue(),
            response.modelUsed()
        );
    }

    /**
     * Parses Claude's JSON response into an exit decision.
     */
    private ExitDecisionParsed parseResponse(String responseText) {
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
            return new ExitDecisionParsed(
                node.path("action").asText("HOLD"),
                node.path("new_stop").isNull() ? null : node.path("new_stop").asDouble(),
                node.path("new_target").isNull() ? null : node.path("new_target").asDouble(),
                node.path("confidence").asDouble(0.5),
                node.path("reasoning").asText("")
            );
        } catch (Exception e) {
            log.error("Failed to parse ExitAgent response: {}", responseText, e);
            throw new RuntimeException("Failed to parse exit agent response: " + e.getMessage(), e);
        }
    }

    /**
     * Internal class to hold parsed exit decision.
     */
    private static class ExitDecisionParsed {
        String action;          // HOLD | CLOSE_NOW | MOVE_TO_BREAKEVEN | TRAIL_TO_X | EXTEND_TARGET_TO_Y
        Double newStop;         // nullable
        Double newTarget;       // nullable
        Double confidence;      // 0.0-1.0
        String reasoning;       // explanation

        ExitDecisionParsed(String action, Double newStop, Double newTarget, Double confidence, String reasoning) {
            this.action = action;
            this.newStop = newStop;
            this.newTarget = newTarget;
            this.confidence = confidence;
            this.reasoning = reasoning;
        }
    }
}
