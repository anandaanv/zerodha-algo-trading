package com.dtech.kitecon.service.copilot;

import com.dtech.kitecon.service.copilot.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class AIResponseParserTest {

    private AIResponseParser parser;
    private ObjectMapper realObjectMapper;

    @BeforeEach
    void setUp() {
        realObjectMapper = new ObjectMapper();
        parser = new AIResponseParser(realObjectMapper);
    }

    @Test
    void testParseFindingResponse() {
        String json = """
            {
              "type": "FINDING",
              "hypothesisLabel": "Wave 5 Launch",
              "hypothesisDescription": "Testing description",
              "waveContext": "Wave position context",
              "pattern": "triangle",
              "currentStage": "ENTRY_READY",
              "confidenceLayers": {},
              "anticipatoryEntry": {},
              "confirmationEntry": {},
              "invalidationConditions": [],
              "anomalyFlags": [],
              "reasoning": "Reasoning here"
            }
            """;

        AIResponse response = parser.parse(json);

        assertNotNull(response, "Response should not be null");
        assertTrue(response instanceof FindingResponse, "Response should be FindingResponse");
    }

    @Test
    void testParseEntrySignalResponse() {
        String json = """
            {
              "type": "ENTRY_SIGNAL",
              "direction": "LONG",
              "entryZone": "22450",
              "stopLoss": "22400",
              "target1": "22500",
              "target2": "22550",
              "rationale": "Strong confluence",
              "reasoning": "Entry reasoning"
            }
            """;

        AIResponse response = parser.parse(json);

        assertNotNull(response, "Response should not be null");
        assertTrue(response instanceof EntrySignalResponse, "Response should be EntrySignalResponse");
    }

    @Test
    void testParseOrchestratorResponse() {
        String json = """
            {
              "type": "ORCHESTRATOR",
              "skillsToInvoke": ["triangle", "wave_4", "confluence_checker"],
              "selectionRationale": "Pattern confirmation across timeframes",
              "analysisComplete": false,
              "completionSummary": null
            }
            """;

        AIResponse response = parser.parse(json);

        assertNotNull(response, "Response should not be null");
        assertTrue(response instanceof OrchestratorResponse, "Response should be OrchestratorResponse");
        OrchestratorResponse orch = (OrchestratorResponse) response;
        assertNotNull(orch.getSkillsToInvoke(), "skillsToInvoke should not be null");
        assertEquals(3, orch.getSkillsToInvoke().size(), "Should have 3 skills");
    }

    @Test
    void testParseInvalidatedResponse() {
        String json = """
            {
              "type": "INVALIDATED",
              "hypothesisId": 123,
              "hypothesisLabel": "Wave 5 Launch",
              "invalidationReason": "Second low broke below first low by 5%",
              "triggerCondition": "Price broke below 22400",
              "alternateHypothesesToCheck": [],
              "linkedTradeExists": false,
              "tradeCloseRecommendation": "None"
            }
            """;

        AIResponse response = parser.parse(json);

        assertNotNull(response, "Response should not be null");
        assertTrue(response instanceof InvalidatedResponse, "Response should be InvalidatedResponse");
    }

    @Test
    void testMalformedJsonReturnsNeedsExpertGracefully() {
        String malformedJson = "{ invalid json content here ]";

        AIResponse response = parser.parse(malformedJson);

        assertNotNull(response, "Response should not be null (graceful degradation)");
        assertTrue(response instanceof NeedsExpertResponse, "Should return NeedsExpertResponse");
        assertEquals(AIResponseType.NEEDS_EXPERT, response.getType(), "Type should be NEEDS_EXPERT");
        NeedsExpertResponse expert = (NeedsExpertResponse) response;
        assertTrue(expert.getQuestionId().startsWith("parse_error_"), "Question ID should start with parse_error_");
    }

    @Test
    void testNullInputReturnsNeedsExpertGracefully() {
        AIResponse response = parser.parse(null);

        assertNotNull(response, "Response should not be null");
        assertTrue(response instanceof NeedsExpertResponse, "Should return NeedsExpertResponse for null");
        assertEquals(AIResponseType.NEEDS_EXPERT, response.getType(), "Type should be NEEDS_EXPERT");
    }

    @Test
    void testEmptyInputReturnsNeedsExpertGracefully() {
        AIResponse response = parser.parse("");

        assertNotNull(response, "Response should not be null");
        assertTrue(response instanceof NeedsExpertResponse, "Should return NeedsExpertResponse for empty");
        assertEquals(AIResponseType.NEEDS_EXPERT, response.getType(), "Type should be NEEDS_EXPERT");
    }

    @Test
    void testStripsMarkdownCodeFences() {
        String jsonWithFences = """
            ```json
            {
              "type": "FINDING",
              "hypothesisLabel": "Wave Test",
              "hypothesisDescription": "Test",
              "waveContext": "Context",
              "pattern": null,
              "currentStage": "WATCHING",
              "confidenceLayers": {},
              "anticipatoryEntry": {},
              "confirmationEntry": {},
              "invalidationConditions": [],
              "anomalyFlags": [],
              "reasoning": "Test"
            }
            ```
            """;

        AIResponse response = parser.parse(jsonWithFences);

        assertNotNull(response, "Response should not be null");
        assertTrue(response instanceof FindingResponse, "Should successfully parse despite markdown fences");
    }

    @Test
    void testStripsMarkdownCodeFencesWithLanguageLabel() {
        String jsonWithFences = """
            ```json
            {"type": "ORCHESTRATOR", "skillsToInvoke": [], "selectionRationale": "test", "analysisComplete": true, "completionSummary": "done"}
            ```
            """;

        AIResponse response = parser.parse(jsonWithFences);

        assertNotNull(response, "Response should not be null");
        assertTrue(response instanceof OrchestratorResponse, "Should parse despite markdown fences");
    }

    @Test
    void testParseObservationResponse() {
        String json = """
            {
              "type": "OBSERVATION",
              "patternDetected": true,
              "patternType": "triangle",
              "confidence": "HIGH",
              "structuralDetails": "Symmetric triangle forming",
              "stage": "FORMING",
              "keyLevels": [],
              "drawingPoints": [],
              "drawingType": "triangle_pattern",
              "timeframe": "1h",
              "contradictions": [],
              "reasoning": "Pattern observation"
            }
            """;

        AIResponse response = parser.parse(json);

        assertNotNull(response, "Response should not be null");
        assertTrue(response instanceof ObservationResponse, "Response should be ObservationResponse");
    }

    @Test
    void testParseNeedsDataResponse() {
        String json = """
            {
              "type": "NEEDS_DATA",
              "timeframe": "1h",
              "dataType": "INDICATORS",
              "dataScope": "last_50_candles",
              "reason": "Need momentum indicators",
              "indicatorsNeeded": ["MACD", "RSI"]
            }
            """;

        AIResponse response = parser.parse(json);

        assertNotNull(response, "Response should not be null");
        assertTrue(response instanceof NeedsDataResponse, "Response should be NeedsDataResponse");
    }

    @Test
    void testParseMonitoringResponse() {
        String json = """
            {
              "type": "MONITORING",
              "statusUpdate": "Pattern developing normally",
              "nextCheckpoint": "Price rejects resistance",
              "reasoning": "Monitoring update"
            }
            """;

        AIResponse response = parser.parse(json);

        assertNotNull(response, "Response should not be null");
        assertTrue(response instanceof MonitoringResponse, "Response should be MonitoringResponse");
    }

    @Test
    void testMissingTypeFieldReturnsNeedsExpert() {
        String jsonNoType = """
            {
              "hypothesisLabel": "Some hypothesis"
            }
            """;

        AIResponse response = parser.parse(jsonNoType);

        assertNotNull(response, "Response should not be null");
        assertTrue(response instanceof NeedsExpertResponse, "Should return NeedsExpertResponse when type missing");
        assertEquals(AIResponseType.NEEDS_EXPERT, response.getType(), "Type should be NEEDS_EXPERT");
    }

    @Test
    void testWhitespaceOnlyInputReturnsNeedsExpert() {
        AIResponse response = parser.parse("   \n  \t  ");

        assertNotNull(response, "Response should not be null");
        assertTrue(response instanceof NeedsExpertResponse, "Should handle whitespace gracefully");
        assertEquals(AIResponseType.NEEDS_EXPERT, response.getType(), "Type should be NEEDS_EXPERT");
    }

    @Test
    void testInvalidTypeValueReturnsNeedsExpert() {
        String jsonInvalidType = """
            {
              "type": "UNKNOWN_TYPE",
              "someData": "value"
            }
            """;

        AIResponse response = parser.parse(jsonInvalidType);

        assertNotNull(response, "Response should not be null");
        assertTrue(response instanceof NeedsExpertResponse, "Should return NeedsExpertResponse for invalid type");
        assertEquals(AIResponseType.NEEDS_EXPERT, response.getType(), "Type should be NEEDS_EXPERT");
    }

    @Test
    void testParseHandlesJsonWithExtraFields() {
        String jsonWithExtra = """
            {
              "type": "ORCHESTRATOR",
              "skillsToInvoke": ["skill1"],
              "selectionRationale": "test",
              "analysisComplete": false,
              "extraField1": "should be ignored",
              "extraField2": 12345
            }
            """;

        AIResponse response = parser.parse(jsonWithExtra);

        assertNotNull(response, "Response should not be null");
        assertTrue(response instanceof OrchestratorResponse, "Should parse despite extra fields");
    }
}
