package com.dtech.aitrader.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiLevelsResponse {
    private String symbol;
    @JsonProperty("generated_at")
    private String generatedAt;
    @JsonProperty("levels_json")
    private Object levelsJson;
    @JsonProperty("cost_usd")
    private BigDecimal costUsd;
    @JsonProperty("model_used")
    private String modelUsed;
    @JsonProperty("input_tokens")
    private Integer inputTokens;
    @JsonProperty("output_tokens")
    private Integer outputTokens;
    @JsonProperty("lines_merged_count")
    private Integer linesMergedCount;
    @JsonProperty("lines_suppressed_count")
    private Integer linesSuppressedCount;
}
