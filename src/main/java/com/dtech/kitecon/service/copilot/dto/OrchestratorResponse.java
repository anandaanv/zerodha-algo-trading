package com.dtech.kitecon.service.copilot.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;

/**
 * Response from the Orchestrator AI call.
 * The orchestrator's only job is to decide which skills to invoke and in what sequence.
 * It contains no trading knowledge itself.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class OrchestratorResponse extends AIResponse {
    /** Ordered list of skill keys to invoke next */
    private List<String> skillsToInvoke;
    /** Brief rationale for why these skills were selected */
    private String selectionRationale;
    /** True when orchestrator determines no more skills are needed this session */
    private boolean analysisComplete;
    /** Summary message to surface in chat when analysis is complete */
    private String completionSummary;
}
