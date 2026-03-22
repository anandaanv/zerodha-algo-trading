package com.dtech.kitecon.service.copilot.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.Map;

/** AI has identified something meaningful — creates or updates a hypothesis. */
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class FindingResponse extends AIResponse {
    private String hypothesisLabel;
    private String hypothesisDescription;
    private String waveContext;
    private String pattern;
    private String currentStage;

    /** Layer name → "pass" | "fail" | "pending" | "warning" */
    private Map<String, String> confidenceLayers;

    private TradeParameters anticipatoryEntry;
    private TradeParameters confirmationEntry;

    private List<String> invalidationConditions;
    private List<String> anomalyFlags;

    /** Relationship type with existing hypotheses, if any */
    private List<HypothesisRelationship> relationships;

    /** Additional skills the orchestrator should invoke next, if any */
    private List<String> suggestNextSkills;

    @Data
    @SuperBuilder
    @NoArgsConstructor
    public static class TradeParameters {
        private String entryZone;
        private String sl;
        private String tp;
        private List<String> conditionsNeeded;
        private String triggerDescription;
    }

    @Data
    @SuperBuilder
    @NoArgsConstructor
    public static class HypothesisRelationship {
        private String relatedHypothesisLabel;
        /** CONFLICTING | SEQUENTIAL | INDEPENDENT | REINFORCING */
        private String relationshipType;
        private String explanation;
    }
}
