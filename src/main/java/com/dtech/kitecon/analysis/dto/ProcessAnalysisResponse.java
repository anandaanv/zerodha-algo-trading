package com.dtech.kitecon.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ProcessingStats {

        @JsonProperty("patterns_before")
        private int patternsBefore;

        @JsonProperty("patterns_after")
        private int patternsAfter;

        @JsonProperty("payload_token_estimate")
        private int payloadTokenEstimate;
    }
}
