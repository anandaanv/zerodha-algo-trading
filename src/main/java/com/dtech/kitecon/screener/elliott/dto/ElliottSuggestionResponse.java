package com.dtech.kitecon.screener.elliott.dto;

import com.dtech.kitecon.screener.elliott.entity.ElliottTradeSuggestion;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ElliottSuggestionResponse {
    private Long id;
    private Long screenerId;
    private Long runId;
    private Long userId;
    private String symbol;
    private String direction;
    private String state;
    private String hypothesisLabel;
    private String waveContext;
    private String pattern;
    private String currentStage;
    private String entryZone;
    private String stopLoss;
    private String target1;
    private String triggerDescription;
    private String reasoning;
    private Map<String, String> confidenceLayers;
    private List<String> invalidationConditions;
    private List<String> anomalyFlags;
    private String userNotes;
    private Instant proposedAt;
    private Instant acceptedAt;
    private Instant activatedAt;
    private Instant closedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public static ElliottSuggestionResponse from(ElliottTradeSuggestion s, ObjectMapper mapper) {
        Map<String, String> confidenceLayers = null;
        List<String> invalidationConditions = null;
        List<String> anomalyFlags = null;
        try {
            if (s.getConfidenceLayersJson() != null) {
                confidenceLayers = mapper.readValue(s.getConfidenceLayersJson(), new TypeReference<>() {});
            }
            if (s.getInvalidationConditionsJson() != null) {
                invalidationConditions = mapper.readValue(s.getInvalidationConditionsJson(), new TypeReference<>() {});
            }
            if (s.getAnomalyFlagsJson() != null) {
                anomalyFlags = mapper.readValue(s.getAnomalyFlagsJson(), new TypeReference<>() {});
            }
        } catch (Exception ignored) {}

        return ElliottSuggestionResponse.builder()
                .id(s.getId())
                .screenerId(s.getScreenerId())
                .runId(s.getRunId())
                .userId(s.getUserId())
                .symbol(s.getSymbol())
                .direction(s.getDirection())
                .state(s.getState().name())
                .hypothesisLabel(s.getHypothesisLabel())
                .waveContext(s.getWaveContext())
                .pattern(s.getPattern())
                .currentStage(s.getCurrentStage())
                .entryZone(s.getEntryZone())
                .stopLoss(s.getStopLoss())
                .target1(s.getTarget1())
                .triggerDescription(s.getTriggerDescription())
                .reasoning(s.getReasoning())
                .confidenceLayers(confidenceLayers)
                .invalidationConditions(invalidationConditions)
                .anomalyFlags(anomalyFlags)
                .userNotes(s.getUserNotes())
                .proposedAt(s.getProposedAt())
                .acceptedAt(s.getAcceptedAt())
                .activatedAt(s.getActivatedAt())
                .closedAt(s.getClosedAt())
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .build();
    }
}
