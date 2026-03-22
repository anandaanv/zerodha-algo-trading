package com.dtech.kitecon.service.copilot;

import com.dtech.kitecon.data.copilot.*;
import com.dtech.kitecon.repository.copilot.*;
import com.dtech.kitecon.service.copilot.dto.FindingResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Manages the hypothesis lifecycle and relationship evaluation.
 *
 * State machine:
 * WATCHING → BUILDING → CONFIRMED → TRADE_ACTIVE → INVALIDATED | EXPIRED
 *
 * Key rules:
 * - Multiple hypotheses per symbol are normal, not an edge case
 * - Hypothesis board is never cleared when a trade is entered
 * - Every invalidation triggers review of alternate hypotheses
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CopilotHypothesisService {

    private final CopilotHypothesisRepository hypothesisRepository;
    private final CopilotHypothesisRelationshipRepository relationshipRepository;
    private final CopilotAnomalyFlagRepository anomalyFlagRepository;
    private final ObjectMapper objectMapper;

    /**
     * Create a new hypothesis from a FINDING AI response.
     */
    @Transactional
    public CopilotHypothesis createFromFinding(Long investigationId, FindingResponse finding) {
        CopilotHypothesis hypothesis = CopilotHypothesis.builder()
                .investigationId(investigationId)
                .symbol(extractSymbol(investigationId))
                .label(finding.getHypothesisLabel())
                .description(finding.getHypothesisDescription())
                .waveContext(finding.getWaveContext())
                .pattern(finding.getPattern())
                .stage(finding.getCurrentStage())
                .state("WATCHING")
                .confidenceLayers(toJson(finding.getConfidenceLayers()))
                .anticipatoryTrade(toJson(finding.getAnticipatoryEntry()))
                .confirmationTrade(toJson(finding.getConfirmationEntry()))
                .invalidationConditions(toJson(finding.getInvalidationConditions()))
                .origin("SYSTEM_SUGGESTED")
                .build();

        hypothesis = hypothesisRepository.save(hypothesis);

        // Create anomaly flags if AI detected any
        if (finding.getAnomalyFlags() != null) {
            for (String flag : finding.getAnomalyFlags()) {
                createAnomalyFlag(investigationId, hypothesis.getId(), flag, "WARNING");
            }
        }

        return hypothesis;
    }

    /** Transition hypothesis state — enforces valid state machine transitions. */
    @Transactional
    public CopilotHypothesis transitionState(Long hypothesisId, String newState, String reason) {
        CopilotHypothesis hypothesis = getOrThrow(hypothesisId);
        String currentState = hypothesis.getState();

        if (!isValidTransition(currentState, newState)) {
            log.warn("Invalid state transition for hypothesis {}: {} → {}", hypothesisId, currentState, newState);
            return hypothesis;
        }

        hypothesis.setState(newState);
        if ("INVALIDATED".equals(newState) && reason != null) {
            hypothesis.setInvalidationReason(reason);
        }
        return hypothesisRepository.save(hypothesis);
    }

    /** Update hypothesis stage and confidence layers from monitoring results. */
    @Transactional
    public CopilotHypothesis updateProgress(Long hypothesisId, String newStage,
                                             Object confidenceLayers, String newState) {
        CopilotHypothesis hypothesis = getOrThrow(hypothesisId);
        if (newStage != null) hypothesis.setStage(newStage);
        if (confidenceLayers != null) hypothesis.setConfidenceLayers(toJson(confidenceLayers));
        if (newState != null && isValidTransition(hypothesis.getState(), newState)) {
            hypothesis.setState(newState);
        }
        return hypothesisRepository.save(hypothesis);
    }

    /** Get all active hypotheses for an investigation. */
    public List<CopilotHypothesis> getActiveHypotheses(Long investigationId) {
        return hypothesisRepository.findByInvestigationIdAndStateIn(investigationId,
                List.of("WATCHING", "BUILDING", "CONFIRMED", "TRADE_ACTIVE"));
    }

    /** Get all hypotheses for an investigation (all states). */
    public List<CopilotHypothesis> getAllHypotheses(Long investigationId) {
        return hypothesisRepository.findByInvestigationId(investigationId);
    }

    /**
     * Evaluate and record relationships between all active hypotheses for a symbol.
     * Called whenever a new hypothesis is created.
     */
    @Transactional
    public void evaluateRelationships(Long investigationId, Long newHypothesisId,
                                       List<FindingResponse.HypothesisRelationship> relationships) {
        if (relationships == null || relationships.isEmpty()) return;

        List<CopilotHypothesis> existing = getActiveHypotheses(investigationId);

        for (FindingResponse.HypothesisRelationship rel : relationships) {
            // Find the hypothesis being referenced by label
            existing.stream()
                    .filter(h -> !h.getId().equals(newHypothesisId) &&
                                 h.getLabel().equalsIgnoreCase(rel.getRelatedHypothesisLabel()))
                    .findFirst()
                    .ifPresent(related -> {
                        // Delete existing relationship between these two if any
                        relationshipRepository.deleteByHypothesisIdOrRelatedHypothesisId(
                                newHypothesisId, related.getId());

                        relationshipRepository.save(CopilotHypothesisRelationship.builder()
                                .hypothesisId(newHypothesisId)
                                .relatedHypothesisId(related.getId())
                                .relationshipType(rel.getRelationshipType())
                                .explanation(rel.getExplanation())
                                .build());
                    });
        }
    }

    /** Get relationships for a hypothesis. */
    public List<CopilotHypothesisRelationship> getRelationships(Long hypothesisId) {
        return relationshipRepository.findByHypothesisIdOrRelatedHypothesisId(hypothesisId, hypothesisId);
    }

    /**
     * When a hypothesis is invalidated, immediately check if any alternate hypothesis
     * now has confirmation conditions met. Per spec: every invalidation = potential new signal.
     */
    public List<CopilotHypothesis> getAlternateHypotheses(Long investigationId, Long invalidatedId) {
        return hypothesisRepository.findByInvestigationIdAndStateIn(investigationId,
                List.of("WATCHING", "BUILDING", "CONFIRMED"))
                .stream()
                .filter(h -> !h.getId().equals(invalidatedId))
                .toList();
    }

    /** Create an anomaly flag requiring expert acknowledgment. */
    @Transactional
    public CopilotAnomalyFlag createAnomalyFlag(Long investigationId, Long hypothesisId,
                                                  String flagText, String level) {
        return anomalyFlagRepository.save(CopilotAnomalyFlag.builder()
                .investigationId(investigationId)
                .hypothesisId(hypothesisId)
                .flagText(flagText)
                .level(level)
                .build());
    }

    /** Check for unacknowledged flags — system must not proceed past unacknowledged flags. */
    public List<CopilotAnomalyFlag> getUnacknowledgedFlags(Long investigationId) {
        return anomalyFlagRepository.findByInvestigationIdAndAcknowledgedFalse(investigationId);
    }

    /** Acknowledge a flag with an action. */
    @Transactional
    public CopilotAnomalyFlag acknowledgeFlag(Long flagId, String action, String notes) {
        CopilotAnomalyFlag flag = anomalyFlagRepository.findById(flagId)
                .orElseThrow(() -> new IllegalArgumentException("Flag not found: " + flagId));
        flag.setAcknowledged(true);
        flag.setAcknowledgedAt(java.time.Instant.now());
        flag.setActionTaken(action);
        flag.setExpertNotes(notes);
        return anomalyFlagRepository.save(flag);
    }

    public CopilotHypothesis getOrThrow(Long id) {
        return hypothesisRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Hypothesis not found: " + id));
    }

    // ─── State machine transitions ───────────────────────────────────────────

    private boolean isValidTransition(String from, String to) {
        return switch (from) {
            case "WATCHING"     -> List.of("BUILDING", "CONFIRMED", "INVALIDATED", "EXPIRED").contains(to);
            case "BUILDING"     -> List.of("CONFIRMED", "INVALIDATED", "EXPIRED").contains(to);
            case "CONFIRMED"    -> List.of("TRADE_ACTIVE", "INVALIDATED", "EXPIRED").contains(to);
            case "TRADE_ACTIVE" -> List.of("INVALIDATED").contains(to);
            default -> false;
        };
    }

    private String extractSymbol(Long investigationId) {
        // Symbol is denormalised from investigation at creation time
        return "UNKNOWN";
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }
}
