package com.dtech.algo.screener.web.dto;

import com.dtech.algo.screener.ScreenerConfig;
import com.dtech.algo.screener.domain.Screener;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;

import java.util.List;
import java.util.Map;

@Builder
public record ScreenerResponse(
        long id,
        String timeframe,
        String script,
        String configJson,
        String promptJson,
        String chartsJson,
        String schedulingConfigJson
) {
    /**
     * Build response from domain Screener by serializing config and charts.
     */
    public static ScreenerResponse fromDomain(Screener s, ObjectMapper objectMapper) {
        try {
            ScreenerConfig cfg = ScreenerConfig.builder()
                    .mapping(s.getMapping() == null ? Map.of() : s.getMapping())
                    .workflow(s.getWorkflow() == null ? List.of() : s.getWorkflow())
                    .build();
            String cfgJson = objectMapper.writeValueAsString(cfg);
            String chartsJson = objectMapper.writeValueAsString(s.getCharts() == null ? List.of() : s.getCharts());
            String schedulingJson = objectMapper.writeValueAsString(
                    s.getSchedulingConfig() == null ? new com.dtech.algo.screener.model.SchedulingConfig() : s.getSchedulingConfig()
            );
            return ScreenerResponse.builder()
                    .id(s.getId())
                    .timeframe(s.getTimeframe())
                    .script(s.getScript())
                    .configJson(cfgJson)
                    .promptJson(s.getPromptJson())
                    .chartsJson(chartsJson)
                    .schedulingConfigJson(schedulingJson)
                    .build();
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to build response: " + e.getMessage(), e);
        }
    }
}
