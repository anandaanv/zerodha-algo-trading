package com.dtech.kitecon.trade.controller;

import com.dtech.aitrader.data.AgentDecision;
import com.dtech.aitrader.data.PaperTrade;
import com.dtech.aitrader.model.ExitDecision;
import com.dtech.aitrader.model.PatternSignal;
import com.dtech.aitrader.repository.PaperTradeRepository;
import com.dtech.aitrader.service.ExitAgentService;
import com.dtech.aitrader.service.PatternAgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST endpoints for PatternAgent decision-making.
 * Manual testing entry point for pattern-based trade decisions.
 */
@RestController
@RequestMapping("/api/ai-pattern")
@RequiredArgsConstructor
@Slf4j
public class AiPatternController {
    private final PatternAgentService patternAgentService;
    private final ExitAgentService exitAgentService;
    private final PaperTradeRepository paperTradeRepository;

    /**
     * POST /api/ai-pattern/decide
     *
     * Takes a PatternSignal, calls Claude for a decision, and returns AgentDecision.
     * Optionally creates a paper_trade row if verdict is TRADE and flags are enabled.
     *
     * Request body:
     * {
     *   "symbol": "WIPRO",
     *   "patternType": "DOUBLE_TOP",
     *   "patternSource": "DTB",
     *   "signalRef": "manual-test",
     *   "direction": "SHORT",
     *   "suggestedEntry": 305.80,
     *   "suggestedSl": 308.50,
     *   "suggestedTarget": 297.50,
     *   "signalTime": "2026-05-12T04:45:00Z",
     *   "timeframe": "OneHour",
     *   "extraContext": { ... }
     * }
     *
     * Response:
     * {
     *   "id": 1,
     *   "verdict": "TRADE",
     *   "direction": "SHORT",
     *   "entry": 305.80,
     *   "sl": 312.00,
     *   "target": 297.50,
     *   "confidence": 0.55,
     *   "reasoning": "...",
     *   "inputTokens": 1234,
     *   "outputTokens": 456,
     *   "costUsd": 0.04,
     *   "modelUsed": "claude-sonnet-4-5-20250929"
     * }
     */
    @PostMapping("/decide")
    public ResponseEntity<Map<String, Object>> decide(
        @RequestBody PatternSignal signal,
        Authentication authentication
    ) {
        log.info("POST /api/ai-pattern/decide: symbol={}, patternType={}, direction={}",
            signal.getSymbol(), signal.getPatternType(), signal.getDirection());

        // Resolve userId from authentication
        Long userId = extractUserId(authentication);

        // Call agent
        AgentDecision decision = patternAgentService.decide(signal, userId);

        // Build response
        Map<String, Object> response = new HashMap<>();
        response.put("id", decision.getId());
        response.put("verdict", decision.getVerdict());
        response.put("direction", decision.getDirection());
        response.put("entry", decision.getEntry());
        response.put("sl", decision.getSl());
        response.put("target", decision.getTarget());
        response.put("confidence", decision.getConfidence());
        response.put("reasoning", decision.getReasoning());
        response.put("inputTokens", decision.getInputTokens());
        response.put("outputTokens", decision.getOutputTokens());
        response.put("costUsd", decision.getCostUsd());
        response.put("modelUsed", decision.getModelUsed());

        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/ai-pattern/exit/decide
     *
     * Takes a paperTradeId, fetches the open trade, calls ExitAgent for a decision.
     *
     * Request body:
     * {
     *   "paperTradeId": 1
     * }
     *
     * Response:
     * {
     *   "action": "HOLD" | "CLOSE_NOW" | "MOVE_TO_BREAKEVEN" | "TRAIL_TO_X" | "EXTEND_TARGET_TO_Y",
     *   "newStop": <number> | null,
     *   "newTarget": <number> | null,
     *   "confidence": 0.0-1.0,
     *   "reasoning": "...",
     *   "inputTokens": 1234,
     *   "outputTokens": 456,
     *   "costUsd": 0.04,
     *   "modelUsed": "claude-sonnet-4-5-20250929"
     * }
     */
    @PostMapping("/exit/decide")
    public ResponseEntity<Map<String, Object>> exitDecide(
        @RequestBody Map<String, Long> request,
        Authentication authentication
    ) {
        Long paperTradeId = request.get("paperTradeId");
        if (paperTradeId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "paperTradeId required"));
        }

        log.info("POST /api/ai-pattern/exit/decide: paperTradeId={}", paperTradeId);

        // Resolve userId from authentication
        Long userId = extractUserId(authentication);

        // Fetch paper trade
        var tradeOpt = paperTradeRepository.findById(paperTradeId);
        if (tradeOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        PaperTrade trade = tradeOpt.get();

        // Call exit agent
        ExitDecision decision = exitAgentService.decide(trade, userId);

        // Build response
        Map<String, Object> response = new HashMap<>();
        response.put("action", decision.action());
        response.put("newStop", decision.newStop());
        response.put("newTarget", decision.newTarget());
        response.put("confidence", decision.confidence());
        response.put("reasoning", decision.reasoning());
        response.put("inputTokens", decision.inputTokens());
        response.put("outputTokens", decision.outputTokens());
        response.put("costUsd", decision.costUsd());
        response.put("modelUsed", decision.modelUsed());

        return ResponseEntity.ok(response);
    }

    /**
     * Extracts userId from Spring Authentication.
     * Assumes principal is a UserDetails-like object with a numeric ID.
     */
    private Long extractUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new RuntimeException("User not authenticated");
        }

        // Try to get userId from principal (expects custom UserDetails with getName() = userId)
        String principalName = authentication.getName();
        try {
            return Long.parseLong(principalName);
        } catch (NumberFormatException e) {
            // If principal name is not numeric, default to 1 (fallback for Anand)
            log.warn("Could not parse principal '{}' as userId, defaulting to 1", principalName);
            return 1L;
        }
    }
}
