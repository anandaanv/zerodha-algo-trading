package com.dtech.kitecon.service.copilot.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

/**
 * Request DTO for Phase 2 (REASON) — cross-correlate observations to find confluence.
 * Accepts 4 input types: prior observations, expert drawings, free-text scenarios, prior hypotheses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReasoningRequest {

    /** Investigation to load stored observations from */
    private Long investigationId;

    /** Specific observation IDs to reason about (null = all positive observations) */
    private List<Long> observationIds;

    /** Expert TradingView drawings as JSON */
    private String drawingsJson;

    /** Free-text scenario description from the user */
    private String scenarioText;

    /** Prior hypothesis IDs to re-evaluate */
    private List<Long> priorHypothesisIds;

    /** Optional: force specific reasoning skill keys (bypass orchestrator selection) */
    private List<String> reasoningSkillKeys;
}
