package com.dtech.algo.screener;

import com.dtech.algo.screener.enums.WorkflowStep;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScreenerConfig {
    // alias -> SeriesSpec mapping for screener execution
    private Map<String, ScreenerContextLoader.SeriesSpec> mapping;

    // Ordered workflow steps for this screener
    private List<WorkflowStep> workflow;
}

