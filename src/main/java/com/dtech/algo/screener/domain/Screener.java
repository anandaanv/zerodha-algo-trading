package com.dtech.algo.screener.domain;

import com.dtech.algo.screener.ScreenerConfig;
import com.dtech.algo.screener.SeriesSpec;
import com.dtech.algo.screener.db.ScreenerEntity;
import com.dtech.algo.screener.enums.WorkflowStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Value;

import java.util.*;

/**
 * Business model for Screener built from ScreenerEntity.
 * Exposes parsed configuration and helper methods.
 */
@Value
@Builder(toBuilder = true)
public class Screener {
    long id;
    String name;
    String script;
    String timeframe;

    String promptId;
    String promptJson;

    Map<String, SeriesSpec> mapping;
    List<WorkflowStep> workflow;
    List<String> charts;   // alias names to send to AI
    List<String> symbols;  // optional subscribed symbols
    com.dtech.algo.screener.model.SchedulingConfig schedulingConfig; // embedded scheduling config

    public String getEffectivePrompt() {
        return (promptId != null && !promptId.isBlank()) ? promptId : promptJson;
    }

    public static Screener fromEntity(ScreenerEntity e, ObjectMapper objectMapper) {
        Objects.requireNonNull(e, "entity must not be null");
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");

        Map<String, SeriesSpec> mapping = Map.of();
        List<WorkflowStep> workflow = List.of();
        try {
            ScreenerConfig cfg = objectMapper.readValue(
                    Optional.ofNullable(e.getConfigJson()).orElse("{}"),
                    ScreenerConfig.class);
            mapping = Optional.ofNullable(cfg.getMapping()).orElse(Map.of());
            workflow = Optional.ofNullable(cfg.getWorkflow()).orElse(List.of());
        } catch (Exception ignore) { }

        List<String> charts = parseStringArray(objectMapper, e.getChartsJson());
        List<String> symbols = parseStringArray(objectMapper, e.getSymbolsJson());

        return Screener.builder()
                .id(Optional.ofNullable(e.getId()).orElse(0L))
                .name(e.getName())
                .script(e.getScript())
                .timeframe(e.getTimeframe())
                .promptId(e.getPromptId())
                .promptJson(e.getPromptJson())
                .mapping(mapping)
                .workflow(workflow)
                .charts(charts)
                .symbols(symbols)
                .schedulingConfig(e.getSchedulingConfig())
                .build();
    }

    /**
     * Convert this domain model into a persistence entity, serializing config and lists.
     */
    public ScreenerEntity toEntity(ObjectMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        ScreenerConfig cfg = ScreenerConfig.builder()
                .mapping(mapping == null ? Map.of() : mapping)
                .workflow(workflow == null ? List.of() : workflow)
                .build();
        String cfgJson;
        String chartsJson;
        String symbolsJson;
        try {
            cfgJson = objectMapper.writeValueAsString(cfg);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize screener config: " + e.getMessage(), e);
        }
        try {
            chartsJson = objectMapper.writeValueAsString(charts == null ? List.of() : charts);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize charts: " + e.getMessage(), e);
        }
        try {
            symbolsJson = objectMapper.writeValueAsString(symbols == null ? List.of() : symbols);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize symbols: " + e.getMessage(), e);
        }

        return ScreenerEntity.builder()
                .id(id == 0L ? null : id)
                .name(name)
                .script(script)
                .timeframe(timeframe)
                .promptId(promptId)
                .promptJson(promptJson)
                .configJson(cfgJson)
                .chartsJson(chartsJson)
                .symbolsJson(symbolsJson)
                .schedulingConfig(schedulingConfig)
                .build();
    }

    private static List<String> parseStringArray(ObjectMapper om, String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            String[] arr = om.readValue(json, String[].class);
            List<String> out = new ArrayList<>();
            for (String s : arr) {
                if (s != null) {
                    String t = s.trim();
                    if (!t.isBlank()) out.add(t);
                }
            }
            return Collections.unmodifiableList(out);
        } catch (Exception ignore) {
            return List.of();
        }
    }
}
