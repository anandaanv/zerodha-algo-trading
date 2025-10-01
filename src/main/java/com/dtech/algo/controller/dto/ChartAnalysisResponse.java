package com.dtech.algo.controller.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Response DTO for the chart analysis API
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChartAnalysisResponse {

    /**
     * Analysis text from the GPT model (for backward compatibility)
     */
    private String analysis;

    /**
     * JSON analysis from the GPT model
     */
    private Object jsonAnalysis;

    /**
     * Map of symbol to analysis text for multi-symbol analysis
     */
    private Map<String, String> symbolAnalysis;

    /**
     * Map of symbol to JSON analysis for multi-symbol analysis
     */
    private Map<String, Object> symbolJsonAnalysis;

}