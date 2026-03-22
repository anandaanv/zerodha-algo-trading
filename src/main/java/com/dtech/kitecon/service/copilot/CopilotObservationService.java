package com.dtech.kitecon.service.copilot;

import com.dtech.kitecon.data.copilot.CopilotObservation;
import com.dtech.kitecon.repository.copilot.CopilotObservationRepository;
import com.dtech.kitecon.service.copilot.dto.ObservationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CopilotObservationService {

    private final CopilotObservationRepository observationRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public CopilotObservation createFromScan(Long investigationId, String skillKey,
                                              ObservationResponse response, String rawResponse) {
        CopilotObservation obs = CopilotObservation.builder()
                .investigationId(investigationId)
                .skillKey(skillKey)
                .patternDetected(response.getPatternDetected() != null && response.getPatternDetected())
                .patternType(response.getPatternType())
                .confidence(response.getConfidence())
                .structuralDetails(response.getStructuralDetails())
                .stage(response.getStage())
                .keyLevels(toJson(response.getKeyLevels()))
                .drawingPoints(toJson(response.getDrawingPoints()))
                .drawingType(response.getDrawingType())
                .timeframe(response.getTimeframe())
                .contradictions(toJson(response.getContradictions()))
                .reasoning(response.getReasoning())
                .rawResponse(rawResponse)
                .build();

        return observationRepository.save(obs);
    }

    public List<CopilotObservation> getObservationsForInvestigation(Long investigationId) {
        return observationRepository.findByInvestigationId(investigationId);
    }

    public List<CopilotObservation> getPositiveObservations(Long investigationId) {
        return observationRepository.findByInvestigationIdAndPatternDetectedTrue(investigationId);
    }

    /**
     * Build a prompt-ready summary of all observations for Phase 2 reasoning.
     */
    public String buildObservationsSummary(Long investigationId) {
        List<CopilotObservation> observations = observationRepository.findByInvestigationId(investigationId);
        if (observations.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("=== SCAN OBSERVATIONS ===\n");
        int idx = 1;
        for (CopilotObservation obs : observations) {
            sb.append(String.format("[%d] %s (%s) — %s, confidence: %s\n",
                    idx++,
                    obs.getPatternType() != null ? obs.getPatternType() : obs.getSkillKey(),
                    obs.getSkillKey(),
                    obs.getPatternDetected() ? "DETECTED" : "NOT DETECTED",
                    obs.getConfidence() != null ? obs.getConfidence() : "N/A"));

            if (obs.getPatternDetected()) {
                if (obs.getStage() != null) sb.append("    Stage: ").append(obs.getStage()).append("\n");
                if (obs.getStructuralDetails() != null) sb.append("    Details: ").append(obs.getStructuralDetails()).append("\n");
                if (obs.getKeyLevels() != null && !obs.getKeyLevels().equals("null") && !obs.getKeyLevels().equals("[]")) {
                    sb.append("    Key levels: ").append(obs.getKeyLevels()).append("\n");
                }
            }
            if (obs.getContradictions() != null && !obs.getContradictions().equals("null") && !obs.getContradictions().equals("[]")) {
                sb.append("    Contradictions: ").append(obs.getContradictions()).append("\n");
            }
            if (obs.getReasoning() != null) sb.append("    Reasoning: ").append(obs.getReasoning()).append("\n");
        }
        return sb.toString();
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("Failed to serialize to JSON: {}", e.getMessage());
            return null;
        }
    }
}
