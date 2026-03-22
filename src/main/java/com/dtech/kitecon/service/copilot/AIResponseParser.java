package com.dtech.kitecon.service.copilot;

import com.dtech.kitecon.service.copilot.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Parses raw AI JSON response strings into typed AIResponse subclasses.
 *
 * Every AI call in the system is expected to return a JSON object with a "type" field
 * matching one of the six AIResponseType values.
 * If parsing fails, returns a NEEDS_EXPERT response asking the expert to retry.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AIResponseParser {

    private final ObjectMapper objectMapper;

    public AIResponse parse(String rawJson) {
        try {
            // Strip markdown code fences if AI wrapped it
            String json = extractJson(rawJson);
            JsonNode root = objectMapper.readTree(json);
            String typeStr = root.path("type").asText("NEEDS_EXPERT");

            return switch (AIResponseType.valueOf(typeStr)) {
                case NEEDS_DATA   -> objectMapper.treeToValue(root, NeedsDataResponse.class);
                case NEEDS_EXPERT -> objectMapper.treeToValue(root, NeedsExpertResponse.class);
                case FINDING      -> objectMapper.treeToValue(root, FindingResponse.class);
                case ENTRY_SIGNAL -> objectMapper.treeToValue(root, EntrySignalResponse.class);
                case MONITORING   -> objectMapper.treeToValue(root, MonitoringResponse.class);
                case INVALIDATED  -> objectMapper.treeToValue(root, InvalidatedResponse.class);
            };
        } catch (Exception e) {
            log.warn("Failed to parse AI response: {}", e.getMessage());
            log.debug("Raw AI response was: {}", rawJson);
            return NeedsExpertResponse.builder()
                    .type(AIResponseType.NEEDS_EXPERT)
                    .questionId("parse_error_" + System.currentTimeMillis())
                    .questionText("The AI returned an unexpected response format. Please review and retry.")
                    .context("Raw response: " + (rawJson != null ? rawJson.substring(0, Math.min(200, rawJson.length())) : "null"))
                    .blocking(false)
                    .questionType("AMBIGUITY")
                    .build();
        }
    }

    /** Strip ```json ... ``` fences that some LLMs add */
    private String extractJson(String raw) {
        if (raw == null) return "{}";
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('\n');
            int end = trimmed.lastIndexOf("```");
            if (start > 0 && end > start) {
                return trimmed.substring(start + 1, end).trim();
            }
        }
        return trimmed;
    }
}
