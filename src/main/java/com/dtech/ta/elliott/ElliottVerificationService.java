package com.dtech.ta.elliott;

import com.dtech.elliott.advanced.domain.scenario.Scenario;
import com.dtech.elliott.advanced.domain.scenario.ScoredScenarioAdapter;
import com.dtech.elliott.advanced.scenario.filter.api.ScenarioFilterEngine;
import com.dtech.elliott.advanced.scenario.filter.config.FilterConfig;
import com.dtech.elliott.advanced.scenario.filter.domain.FilteredScenarioSet;
import com.dtech.kitecon.service.copilot.CopilotAIService;
import com.dtech.ta.elliott.scenario.ScoredScenario;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ElliottVerificationService {

    private final ScenarioFilterEngine filterEngine;
    private final CopilotAIService aiService;
    private final ObjectMapper objectMapper;

    private static final String AI_INSTRUCTIONS =
            "You are an Elliott Wave analyst. Review this filtered scenario set and confirm or challenge the leading scenario. " +
            "Return your assessment as JSON with fields: confirmed (boolean), adjustedLeading (string family type or null), " +
            "reasoning (string), confidence (0.0-1.0).";

    public VerifiedElliottResult verify(
            List<ScoredScenario> scoredScenarios,
            String symbol,
            String anchorTimeframe,
            Long userId,
            FilterConfig config) {

        // Step 1: Bridge first-pass → second-pass input
        List<Scenario> raw = ScoredScenarioAdapter.toScenarios(scoredScenarios, symbol, anchorTimeframe);

        // Step 2: Run second-pass filter
        FilteredScenarioSet filtered = filterEngine.filter(raw, config);

        // Step 3: Call AI for verification
        String aiRawResponse = null;
        boolean aiConfirmed = false;
        String aiReasoning = "AI verification unavailable";
        double aiConfidence = 0.0;

        try {
            String payloadJson = objectMapper.writeValueAsString(filtered.reasoningPayloadCompression());
            aiRawResponse = aiService.call(userId, AI_INSTRUCTIONS, payloadJson);
            log.debug("ElliottVerificationService AI raw response: {}", aiRawResponse);

            if (aiRawResponse != null && !aiRawResponse.isBlank()) {
                aiConfirmed = aiRawResponse.contains("\"confirmed\":true");
                aiReasoning = extractField(aiRawResponse, "reasoning", aiRawResponse);
                aiConfidence = extractDouble(aiRawResponse, "confidence", 0.5);
            }
        } catch (Exception e) {
            log.warn("ElliottVerificationService: AI verification failed: {}", e.getMessage());
        }

        return new VerifiedElliottResult(filtered, aiRawResponse, aiConfirmed, aiReasoning, aiConfidence);
    }

    private String extractField(String json, String field, String fallback) {
        try {
            // Simple extraction: find "field":"value" or "field": "value"
            String key = "\"" + field + "\"";
            int idx = json.indexOf(key);
            if (idx < 0) return fallback;
            int colon = json.indexOf(':', idx);
            if (colon < 0) return fallback;
            int start = json.indexOf('"', colon + 1);
            if (start < 0) return fallback;
            int end = json.indexOf('"', start + 1);
            if (end < 0) return fallback;
            return json.substring(start + 1, end);
        } catch (Exception e) {
            return fallback;
        }
    }

    private double extractDouble(String json, String field, double fallback) {
        try {
            String key = "\"" + field + "\"";
            int idx = json.indexOf(key);
            if (idx < 0) return fallback;
            int colon = json.indexOf(':', idx);
            if (colon < 0) return fallback;
            // Find numeric value after colon
            int start = colon + 1;
            while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\n')) start++;
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.')) end++;
            if (start == end) return fallback;
            return Double.parseDouble(json.substring(start, end));
        } catch (Exception e) {
            return fallback;
        }
    }
}
