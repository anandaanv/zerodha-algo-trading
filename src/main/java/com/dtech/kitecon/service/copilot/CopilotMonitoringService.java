package com.dtech.kitecon.service.copilot;

import com.dtech.kitecon.data.copilot.CopilotHypothesis;
import com.dtech.kitecon.data.copilot.CopilotInvestigation;
import com.dtech.kitecon.service.copilot.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Entry monitoring service — runs confirmation skill checks on each candle close.
 *
 * Phase 1: Interface stubbed. Backend-triggered via REST endpoint.
 * Future Phase: WebSocket push after investigation completes.
 *
 * Design principle: The trigger mechanism is abstracted so that future background
 * scanning can use the same pipeline with a different trigger (scheduler vs REST).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CopilotMonitoringService {

    private final CopilotInvestigationService investigationService;
    private final CopilotHypothesisService hypothesisService;
    private final CopilotSkillService skillService;
    private final CopilotAIService aiService;
    private final AIResponseParser responseParser;

    /**
     * Process a new candle close for a symbol.
     * Checks all active hypotheses in monitoring state for entry signals or invalidations.
     *
     * Called from: POST /api/monitor/candle-close
     * Future: called by scheduler or WebSocket event handler
     *
     * @param symbol      the trading symbol
     * @param userId      user whose investigation to check
     * @param candleData  latest candle + indicator values as JSON string
     * @return list of response types generated (ENTRY_SIGNAL, MONITORING, INVALIDATED)
     */
    @Async("copilotExecutor")
    public void processNewCandle(Long investigationId, Long userId, String symbol,
                                  String timeframe, String candleData) {
        try {
            CopilotInvestigation investigation = investigationService.getOrThrow(investigationId);

            if (investigation.isExpired()) {
                log.debug("Skipping candle close for expired investigation {}", investigationId);
                return;
            }

            List<CopilotHypothesis> monitoringHypotheses = hypothesisService
                    .getActiveHypotheses(investigationId)
                    .stream()
                    .filter(h -> "CONFIRMED".equals(h.getState()) || "BUILDING".equals(h.getState()))
                    .toList();

            if (monitoringHypotheses.isEmpty()) {
                log.debug("No hypotheses in monitoring state for investigation {}", investigationId);
                return;
            }

            for (CopilotHypothesis hypothesis : monitoringHypotheses) {
                checkHypothesisOnCandleClose(investigation, hypothesis, userId, candleData, timeframe);
            }
        } catch (Exception e) {
            log.error("Error processing candle close for investigation {}: {}", investigationId, e.getMessage());
        }
    }

    private void checkHypothesisOnCandleClose(CopilotInvestigation investigation,
                                               CopilotHypothesis hypothesis,
                                               Long userId, String candleData, String timeframe) {
        // Load the relevant confirmation skill for this pattern
        String skillKey = hypothesis.getPattern(); // e.g. "triangle", "double_bottom"
        var skillOpt = skillService.getSkillByKey(userId, skillKey);

        if (skillOpt.isEmpty()) {
            log.debug("No skill found for pattern {} — skipping candle check", skillKey);
            return;
        }

        String skillPrompt = skillService.buildSkillPrompt(skillOpt.get());

        // Build context string for the AI call
        String context = buildCandleCheckContext(investigation, hypothesis, candleData, timeframe);

        try {
            String rawResponse = aiService.call(userId, skillPrompt, context);
            AIResponse response = responseParser.parse(rawResponse);

            handleMonitoringResponse(response, investigation, hypothesis);
        } catch (Exception e) {
            log.error("AI call failed during candle monitoring for hypothesis {}: {}",
                    hypothesis.getId(), e.getMessage());
        }
    }

    private void handleMonitoringResponse(AIResponse response, CopilotInvestigation investigation,
                                           CopilotHypothesis hypothesis) {
        switch (response.getType()) {
            case ENTRY_SIGNAL -> {
                // Notify expert — move hypothesis to CONFIRMED if not already
                hypothesisService.transitionState(hypothesis.getId(), "CONFIRMED", null);
                log.info("ENTRY_SIGNAL for hypothesis {} in investigation {}",
                        hypothesis.getId(), investigation.getId());
                // TODO Phase 5: push to WebSocket / chat notification
            }
            case MONITORING -> {
                MonitoringResponse mon = (MonitoringResponse) response;
                hypothesisService.updateProgress(hypothesis.getId(), null, null, "BUILDING");
                log.debug("Monitoring update for hypothesis {}: {}", hypothesis.getId(), mon.getProgressSummary());
            }
            case INVALIDATED -> {
                InvalidatedResponse inv = (InvalidatedResponse) response;
                hypothesisService.transitionState(hypothesis.getId(), "INVALIDATED", inv.getInvalidationReason());
                log.info("Hypothesis {} invalidated: {}", hypothesis.getId(), inv.getInvalidationReason());
                // Check alternates — per spec: every invalidation = potential new signal
                List<CopilotHypothesis> alternates = hypothesisService
                        .getAlternateHypotheses(investigation.getId(), hypothesis.getId());
                if (!alternates.isEmpty()) {
                    log.info("Reviewing {} alternate hypotheses after invalidation", alternates.size());
                }
            }
            case NEEDS_EXPERT -> {
                NeedsExpertResponse needsExpert = (NeedsExpertResponse) response;
                hypothesisService.createAnomalyFlag(investigation.getId(), hypothesis.getId(),
                        needsExpert.getQuestionText(), "WARNING");
                log.info("Expert input required for hypothesis {}: {}", hypothesis.getId(),
                        needsExpert.getQuestionText());
            }
            default -> log.debug("Unhandled response type during monitoring: {}", response.getType());
        }
    }

    private String buildCandleCheckContext(CopilotInvestigation investigation,
                                            CopilotHypothesis hypothesis,
                                            String candleData, String timeframe) {
        return String.format("""
                MONITORING CHECK — CANDLE CLOSE

                Symbol: %s
                Timeframe: %s
                Hypothesis: %s (State: %s, Pattern: %s, Stage: %s)
                Wave context: %s

                Latest candle + indicators:
                %s

                Current confidence layers:
                %s

                Invalidation conditions to check:
                %s

                Respond with ENTRY_SIGNAL if entry conditions are met,
                MONITORING if still waiting,
                INVALIDATED if an invalidation condition has been triggered,
                or NEEDS_EXPERT if you require expert clarification.
                """,
                investigation.getSymbol(), timeframe,
                hypothesis.getLabel(), hypothesis.getState(), hypothesis.getPattern(), hypothesis.getStage(),
                hypothesis.getWaveContext(),
                candleData,
                hypothesis.getConfidenceLayers(),
                hypothesis.getInvalidationConditions());
    }
}
