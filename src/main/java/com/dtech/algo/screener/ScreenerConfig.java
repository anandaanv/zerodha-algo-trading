package com.dtech.algo.screener;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScreenerConfig {
    // alias -> SeriesSpec mapping for ScreenerContextLoader
    private Map<String, ScreenerContextLoader.SeriesSpec> mapping;

    // Ordered workflow steps for this screener
    @Builder.Default
    private List<WorkflowStep> workflow = List.of(WorkflowStep.SCRIPT);
}

enum WorkflowStep {
    SCRIPT,
    OPENAI
}
