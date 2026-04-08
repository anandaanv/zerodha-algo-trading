package com.dtech.kitecon.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessAnalysisResponse {

    @JsonProperty("human_readable")
    private String humanReadable;

    @JsonProperty("ai_payload")
    private Object aiPayload;

    @JsonProperty("processing_stats")
    private ProcessingStats processingStats;

    /** Filtered, deduplicated patterns that survived all lifecycle checks */
    private List<com.dtech.ta.elliott.PatternMatch> activePatterns;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ProcessingStats {

        @JsonProperty("patterns_before")
        private int patternsBefore;

        @JsonProperty("patterns_after")
        private int patternsAfter;

        @JsonProperty("patterns_removed_historical")
        private int patternsRemovedHistorical;

        @JsonProperty("patterns_removed_proximity")
        private int patternsRemovedProximity;

        @JsonProperty("patterns_removed_lifecycle")
        private int patternsRemovedLifecycle;

        @JsonProperty("wave_counts_historical")
        private int waveCountsHistorical;

        @JsonProperty("wave_counts_active")
        private int waveCountsActive;

        @JsonProperty("hypotheses_suppressed_historical")
        private int hypothesesSuppressedHistorical;

        @JsonProperty("payload_token_estimate")
        private int payloadTokenEstimate;
    }
}
