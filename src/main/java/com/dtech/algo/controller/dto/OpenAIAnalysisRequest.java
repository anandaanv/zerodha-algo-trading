package com.dtech.algo.controller.dto;

import com.dtech.algo.screener.ScreenerContextLoader;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Request DTO for OpenAI-backed screening/analysis workflow.
 * Includes symbol, series mapping context, and prompt references.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenAIAnalysisRequest {

    /**
     * Trading symbol to analyze (e.g., "AAPL", "NIFTY", "BTCUSDT").
     */
    private String symbol;

    /**
     * Screener context mapping that describes the series/indicators to prepare.
     * This can be used by downstream services to decide chart composition.
     */
    private Map<String, ScreenerContextLoader.SeriesSpec> mapping;

    /**
     * Optional prompt identifier (e.g., a stored/template prompt reference).
     */
    private String promptId;

    /**
     * Optional raw prompt JSON or template content to send to OpenAI.
     */
    private String promptJson;

    /**
     * Resolve the effective prompt to use.
     * Precedence: promptId (if non-blank) else promptJson.
     */
    public String getEffectivePrompt() {
        if (promptId != null && !promptId.isBlank()) {
            return promptId;
        }
        return promptJson;
    }
}
