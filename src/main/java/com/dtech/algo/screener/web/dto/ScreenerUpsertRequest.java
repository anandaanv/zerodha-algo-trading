package com.dtech.algo.screener.web.dto;

import com.dtech.algo.screener.model.RunConfig;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScreenerUpsertRequest {
    private String script;
    private String timeframe;

    // Will be serialized into configJson
    // mapping: structure aligned with backend expectations
    private Map<String, Object> mapping;
    // workflow steps, e.g., ["SCRIPT", "OPENAI"]
    private List<String> workflow;

    // Stored as-is into promptJson (stringified JSON)
    private String promptJson;

    // Optional prompt id; if provided, it takes precedence over promptJson
    private String promptId;

    // Chart aliases to send to AI; stored as JSON array string in chartsJson
    private List<String> charts;

    // Scheduling: array of run configurations to embed into ScreenerEntity.schedulingConfig
    private List<RunConfig> runConfigs;
}
