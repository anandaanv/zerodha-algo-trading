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
        private String direction;
        private String entryZone;
        private String sl;
        private String tp;
        private String stopLoss;   // AI sometimes returns stopLoss instead of sl
        private String target1;    // AI sometimes returns target1 instead of tp
        private List<String> conditionsNeeded;
        private String triggerDescription;

        public String resolvedSl() {
            return sl != null ? sl : stopLoss;
        }
        public String resolvedTp() {
            return tp != null ? tp : target1;
        }
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
