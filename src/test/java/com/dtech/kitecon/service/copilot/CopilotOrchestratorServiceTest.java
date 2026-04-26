package com.dtech.kitecon.service.copilot;

import com.dtech.kitecon.data.copilot.CopilotInvestigation;
import com.dtech.kitecon.data.copilot.CopilotOrchestratorConfig;
import com.dtech.kitecon.data.copilot.CopilotSkill;
import com.dtech.kitecon.repository.copilot.CopilotOrchestratorConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CopilotOrchestratorServiceTest {

    @Mock
    private CopilotSkillService skillService;

    @Mock
    private CopilotInvestigationService investigationService;

    @Mock
    private CopilotAIService aiService;

    @Mock
    private AIResponseParser responseParser;

    @Mock
    private CopilotOrchestratorConfigRepository orchestratorConfigRepository;

    private ObjectMapper objectMapper;

    @InjectMocks
    private CopilotOrchestratorService orchestratorService;

    private CopilotInvestigation investigation;
    private CopilotSkill triangleSkill;
    private CopilotSkill wave4Skill;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        orchestratorService = new CopilotOrchestratorService(
                skillService, investigationService, aiService, responseParser, objectMapper, orchestratorConfigRepository);

        investigation = CopilotInvestigation.builder()
                .id(1L)
                .layoutId(10L)
                .userId(100L)
                .symbol("NIFTY")
                .timeframesActive("1h,4h")
                .waveCountConfirmed(false)
                .invokedSkills("[\"triangle\"]")
                .skillResults("{}")
                .marketStructureData("Uptrend in progress")
                .zigzagData("Pivot data")
                .drawingsData("Expert annotations")
                .build();

        triangleSkill = CopilotSkill.builder()
                .id(1L)
                .userId(100L)
                .name("Triangle Pattern")
                .description("Triangle pattern detection")
                .category("PATTERN")
                .skillKey("triangle")
                .isActive(true)
                .build();

        wave4Skill = CopilotSkill.builder()
                .id(2L)
                .userId(100L)
                .name("Wave 4 Pattern")
                .description("Elliott Wave 4 analysis")
                .category("WAVE")
                .skillKey("wave_4")
                .isActive(true)
                .build();
    }

    @Test
    void testGetDefaultInstructions() {
        String instructions = orchestratorService.getDefaultInstructions();

        assertNotNull(instructions, "Instructions should not be null");
        assertFalse(instructions.isBlank(), "Instructions should not be empty");
        assertTrue(instructions.contains("ORCHESTRATOR"), "Should contain ORCHESTRATOR reference");
        assertTrue(instructions.contains("skillsToInvoke"), "Should mention skillsToInvoke");
    }

    @Test
    void testGetInstructionsForUserWhenNoCustom() {
        Long userId = 100L;
        when(orchestratorConfigRepository.findByUserId(userId))
                .thenReturn(Optional.empty());

        String instructions = orchestratorService.getInstructionsForUser(userId);

        assertNotNull(instructions, "Instructions should not be null");
        assertTrue(instructions.contains("ORCHESTRATOR"), "Should return default instructions");
        verify(orchestratorConfigRepository, times(1)).findByUserId(userId);
    }

    @Test
    void testGetInstructionsForUserWhenCustomExists() {
        Long userId = 100L;
        String customInstructions = "Custom orchestrator rules for user 100";
        CopilotOrchestratorConfig config = CopilotOrchestratorConfig.builder()
                .id(1L)
                .userId(userId)
                .instructions(customInstructions)
                .build();

        when(orchestratorConfigRepository.findByUserId(userId))
                .thenReturn(Optional.of(config));

        String instructions = orchestratorService.getInstructionsForUser(userId);

        assertEquals(customInstructions, instructions, "Should return custom instructions");
        verify(orchestratorConfigRepository, times(1)).findByUserId(userId);
    }

    @Test
    void testIsCustomizedWhenNoCustom() {
        Long userId = 100L;
        when(orchestratorConfigRepository.findByUserId(userId))
                .thenReturn(Optional.empty());

        boolean customized = orchestratorService.isCustomized(userId);

        assertFalse(customized, "Should return false when no custom config");
        verify(orchestratorConfigRepository, times(1)).findByUserId(userId);
    }

    @Test
    void testIsCustomizedWhenCustomExists() {
        Long userId = 100L;
        CopilotOrchestratorConfig config = CopilotOrchestratorConfig.builder()
                .userId(userId)
                .instructions("Custom")
                .build();

        when(orchestratorConfigRepository.findByUserId(userId))
                .thenReturn(Optional.of(config));

        boolean customized = orchestratorService.isCustomized(userId);

        assertTrue(customized, "Should return true when custom config exists");
    }

    @Test
    void testSaveInstructionsForUser() {
        Long userId = 100L;
        String instructions = "New custom instructions";
        CopilotOrchestratorConfig config = CopilotOrchestratorConfig.builder()
                .userId(userId)
                .instructions(instructions)
                .build();

        when(orchestratorConfigRepository.findByUserId(userId))
                .thenReturn(Optional.empty());
        when(orchestratorConfigRepository.save(any()))
                .thenReturn(config);

        CopilotOrchestratorConfig saved = orchestratorService.saveInstructionsForUser(userId, instructions);

        assertNotNull(saved, "Should return saved config");
        assertEquals(instructions, saved.getInstructions(), "Should have correct instructions");
        verify(orchestratorConfigRepository, times(1)).save(any());
    }

    @Test
    void testResetToDefault() {
        Long userId = 100L;
        CopilotOrchestratorConfig config = CopilotOrchestratorConfig.builder()
                .userId(userId)
                .instructions("Custom")
                .build();

        when(orchestratorConfigRepository.findByUserId(userId))
                .thenReturn(Optional.of(config));

        orchestratorService.resetToDefault(userId);

        verify(orchestratorConfigRepository, times(1)).delete(config);
    }

    @Test
    void testResetToDefaultWhenNoCustom() {
        Long userId = 100L;
        when(orchestratorConfigRepository.findByUserId(userId))
                .thenReturn(Optional.empty());

        orchestratorService.resetToDefault(userId);

        verify(orchestratorConfigRepository, times(0)).delete(any());
    }

    @Test
    void testIsSkillAlreadyInvoked() {
        CopilotInvestigation inv = CopilotInvestigation.builder()
                .id(1L)
                .layoutId(10L)
                .userId(100L)
                .symbol("NIFTY")
                .timeframesActive("1h,4h")
                .invokedSkills("[\"triangle\", \"wave_4\"]")
                .build();

        boolean invoked1 = orchestratorService.isSkillAlreadyInvoked(inv, "triangle");
        boolean invoked2 = orchestratorService.isSkillAlreadyInvoked(inv, "wave_4");
        boolean notInvoked = orchestratorService.isSkillAlreadyInvoked(inv, "confluence_checker");

        assertTrue(invoked1, "triangle should be invoked");
        assertTrue(invoked2, "wave_4 should be invoked");
        assertFalse(notInvoked, "confluence_checker should not be invoked");
    }

    @Test
    void testIsSkillAlreadyInvokedEmptyList() {
        CopilotInvestigation inv = CopilotInvestigation.builder()
                .id(1L)
                .layoutId(10L)
                .userId(100L)
                .symbol("NIFTY")
                .timeframesActive("1h,4h")
                .invokedSkills("[]")
                .build();

        boolean notInvoked = orchestratorService.isSkillAlreadyInvoked(inv, "triangle");

        assertFalse(notInvoked, "Should return false for empty invoked skills");
    }

    @Test
    void testIsSkillAlreadyInvokedNullList() {
        CopilotInvestigation inv = CopilotInvestigation.builder()
                .id(1L)
                .layoutId(10L)
                .userId(100L)
                .symbol("NIFTY")
                .timeframesActive("1h,4h")
                .invokedSkills(null)
                .build();

        boolean notInvoked = orchestratorService.isSkillAlreadyInvoked(inv, "triangle");

        assertFalse(notInvoked, "Should return false for null invoked skills");
    }

    @Test
    void testFilterCycleSkills() {
        CopilotInvestigation inv = CopilotInvestigation.builder()
                .id(1L)
                .layoutId(10L)
                .userId(100L)
                .symbol("NIFTY")
                .timeframesActive("1h,4h")
                .invokedSkills("[\"triangle\"]")
                .build();

        List<String> requested = Arrays.asList("triangle", "wave_4", "confluence_checker");
        List<String> filtered = orchestratorService.filterCycleSkills(requested, inv);

        assertEquals(2, filtered.size(), "Should filter out triangle");
        assertTrue(filtered.contains("wave_4"), "Should include wave_4");
        assertTrue(filtered.contains("confluence_checker"), "Should include confluence_checker");
        assertFalse(filtered.contains("triangle"), "Should not include already invoked triangle");
    }

    @Test
    void testFilterCycleSkillsEmpty() {
        CopilotInvestigation inv = CopilotInvestigation.builder()
                .id(1L)
                .layoutId(10L)
                .userId(100L)
                .symbol("NIFTY")
                .timeframesActive("1h,4h")
                .invokedSkills("[]")
                .build();

        List<String> requested = Arrays.asList("triangle", "wave_4");
        List<String> filtered = orchestratorService.filterCycleSkills(requested, inv);

        assertEquals(2, filtered.size(), "Should not filter anything");
    }

    @Test
    void testFilterCycleSkillsNull() {
        List<String> filtered = orchestratorService.filterCycleSkills(null, investigation);

        assertTrue(filtered.isEmpty(), "Should return empty list for null input");
    }

    @Test
    void testGetInvokedSkillKeys() {
        CopilotInvestigation inv = CopilotInvestigation.builder()
                .id(1L)
                .layoutId(10L)
                .userId(100L)
                .symbol("NIFTY")
                .timeframesActive("1h,4h")
                .invokedSkills("[\"triangle\", \"wave_4\"]")
                .build();

        List<String> keys = orchestratorService.getInvokedSkillKeys(inv);

        assertEquals(2, keys.size(), "Should parse 2 skill keys");
        assertTrue(keys.contains("triangle"), "Should contain triangle");
        assertTrue(keys.contains("wave_4"), "Should contain wave_4");
    }

    @Test
    void testGetInvokedSkillKeysEmpty() {
        CopilotInvestigation inv = CopilotInvestigation.builder()
                .id(1L)
                .layoutId(10L)
                .userId(100L)
                .symbol("NIFTY")
                .timeframesActive("1h,4h")
                .invokedSkills("[]")
                .build();

        List<String> keys = orchestratorService.getInvokedSkillKeys(inv);

        assertTrue(keys.isEmpty(), "Should return empty list");
    }

    @Test
    void testGetInvokedSkillKeysNull() {
        CopilotInvestigation inv = CopilotInvestigation.builder()
                .id(1L)
                .layoutId(10L)
                .userId(100L)
                .symbol("NIFTY")
                .timeframesActive("1h,4h")
                .invokedSkills(null)
                .build();

        List<String> keys = orchestratorService.getInvokedSkillKeys(inv);

        assertTrue(keys.isEmpty(), "Should return empty list for null");
    }

    @Test
    void testBuildOrchestratorPrompt() {
        Long userId = 100L;
        List<CopilotSkill> skills = Arrays.asList(triangleSkill, wave4Skill);

        when(orchestratorConfigRepository.findByUserId(userId))
                .thenReturn(Optional.empty());

        String prompt = orchestratorService.buildOrchestratorPrompt(userId, skills, investigation, Optional.empty());

        assertNotNull(prompt, "Prompt should not be null");
        assertTrue(prompt.contains("AVAILABLE SKILLS"), "Should list available skills");
        assertTrue(prompt.contains("triangle"), "Should mention triangle skill");
        assertTrue(prompt.contains("wave_4"), "Should mention wave_4 skill");
        assertTrue(prompt.contains("ALREADY INVOKED"), "Should show already invoked skills");
        assertTrue(prompt.contains("INVESTIGATION CONTEXT"), "Should include investigation context");
        assertTrue(prompt.contains("NIFTY"), "Should include symbol");
    }

    @Test
    void testBuildOrchestratorPromptWithPreviousInvestigation() {
        Long userId = 100L;
        List<CopilotSkill> skills = Arrays.asList(triangleSkill);
        CopilotInvestigation previousInv = CopilotInvestigation.builder()
                .id(1L)
                .layoutId(10L)
                .userId(100L)
                .symbol("NIFTY")
                .timeframesActive("1h,4h")
                .skillResults("Previous results here")
                .build();

        when(orchestratorConfigRepository.findByUserId(userId))
                .thenReturn(Optional.empty());

        String prompt = orchestratorService.buildOrchestratorPrompt(userId, skills, investigation, Optional.of(previousInv));

        assertNotNull(prompt, "Prompt should not be null");
        assertTrue(prompt.contains("PREVIOUS INVESTIGATION"), "Should include previous investigation");
        assertTrue(prompt.contains("Previous results"), "Should include previous results");
    }

    @Test
    void testBuildSkillPrompt() {
        String skillPrompt = "Triangle Pattern Rules...";
        String result = orchestratorService.buildSkillPrompt(skillPrompt, investigation);

        assertNotNull(result, "Prompt should not be null");
        assertTrue(result.contains("specialised trading skill"), "Should mention skill execution");
        assertTrue(result.contains(skillPrompt), "Should include skill prompt");
        assertTrue(result.contains("INVESTIGATION CONTEXT"), "Should include investigation context");
        assertTrue(result.contains("NIFTY"), "Should include symbol");
    }

    @Test
    void testBuildScanPrompt() {
        String skillPrompt = "Triangle Scan Rules...";
        String result = orchestratorService.buildScanPrompt(skillPrompt, investigation);

        assertNotNull(result, "Prompt should not be null");
        assertTrue(result.contains("SCAN MODE"), "Should indicate scan mode");
        assertTrue(result.contains("OBSERVATION"), "Should mention OBSERVATION response type");
        assertTrue(result.contains(skillPrompt), "Should include skill prompt");
        assertTrue(result.contains("INVESTIGATION CONTEXT"), "Should include investigation context");
    }

    @Test
    void testBuildReasoningPrompt() {
        String skillPrompt = "Confluence Reasoning...";
        String observationsSummary = "Observations from previous scans";
        String drawingsJson = "{drawings}";
        String scenarioText = "User scenario";
        String priorHypothesesJson = "{hypotheses}";

        String result = orchestratorService.buildReasoningPrompt(
                skillPrompt, investigation, observationsSummary, drawingsJson, scenarioText, priorHypothesesJson);

        assertNotNull(result, "Prompt should not be null");
        assertTrue(result.contains(skillPrompt), "Should include skill prompt");
        assertTrue(result.contains(observationsSummary), "Should include observations");
        assertTrue(result.contains(drawingsJson), "Should include drawings");
        assertTrue(result.contains(scenarioText), "Should include scenario");
        assertTrue(result.contains(priorHypothesesJson), "Should include prior hypotheses");
    }

    @Test
    void testBuildScenarioEvaluatorInstructions() {
        String instructions = orchestratorService.buildScenarioEvaluatorInstructions();

        assertNotNull(instructions, "Instructions should not be null");
        assertFalse(instructions.isBlank(), "Instructions should not be empty");
        assertTrue(instructions.contains("Elliott Wave"), "Should mention Elliott Wave");
        assertTrue(instructions.contains("FINDING"), "Should mention FINDING response type");
    }

    @Test
    void testBuildScenarioEvaluatorData() {
        CopilotInvestigation inv = CopilotInvestigation.builder()
                .id(1L)
                .layoutId(10L)
                .userId(100L)
                .symbol("NIFTY")
                .timeframesActive("1h,4h")
                .elliottAnalysisData("Elliott wave data")
                .marketStructureData("Market structure data")
                .build();

        String data = orchestratorService.buildScenarioEvaluatorData(inv);

        assertNotNull(data, "Data should not be null");
        assertTrue(data.contains("NIFTY"), "Should include symbol");
        assertTrue(data.contains("Elliott wave data"), "Should include Elliott analysis");
        assertTrue(data.contains("MARKET STRUCTURE"), "Should include market structure section");
    }

    @Test
    void testBuildScenarioEvaluatorDataWithoutElliottData() {
        CopilotInvestigation inv = CopilotInvestigation.builder()
                .id(1L)
                .layoutId(10L)
                .userId(100L)
                .symbol("NIFTY")
                .timeframesActive("1h,4h")
                .elliottAnalysisData(null)
                .build();

        String data = orchestratorService.buildScenarioEvaluatorData(inv);

        assertNotNull(data, "Data should not be null");
        assertTrue(data.contains("No pre-computed Elliott"), "Should indicate missing Elliott data");
    }

    @Test
    void testBuildInvestigationSummary() {
        String summary = orchestratorService.buildInvestigationSummary(investigation);

        assertNotNull(summary, "Summary should not be null");
        assertTrue(summary.contains("NIFTY"), "Should include symbol");
        assertTrue(summary.contains("1h,4h"), "Should include timeframes");
        assertTrue(summary.contains("false"), "Should include wave count status");
    }

    @Test
    void testCycleDetectionScenario() {
        CopilotInvestigation inv = CopilotInvestigation.builder()
                .id(1L)
                .layoutId(10L)
                .userId(100L)
                .symbol("NIFTY")
                .timeframesActive("1h,4h")
                .invokedSkills("[\"triangle\", \"wave_4\"]")
                .build();

        List<String> requestedSkills = Arrays.asList("triangle", "wave_4", "confluence_checker");
        List<String> filtered = orchestratorService.filterCycleSkills(requestedSkills, inv);

        assertEquals(1, filtered.size(), "Should only allow confluence_checker");
        assertEquals("confluence_checker", filtered.get(0), "Should be confluence_checker");
    }

    @Test
    void testValidateInstructionsStructure() {
        // Test that validateInstructions returns correct Map structure
        // Note: Actual AI call validation skipped to avoid network/timeout issues
        Long userId = 100L;
        String instructions = "Test instructions";

        when(orchestratorConfigRepository.findByUserId(userId))
                .thenReturn(Optional.empty());
        when(aiService.call(eq(userId), any(), any()))
                .thenReturn("{\"type\": \"ORCHESTRATOR\", \"skillsToInvoke\": [], \"selectionRationale\": \"test\", \"analysisComplete\": true}");

        Map<String, Object> result = orchestratorService.validateInstructions(userId, instructions);

        assertNotNull(result, "Result map should not be null");
        assertTrue(result.containsKey("valid"), "Should have 'valid' key");
        assertTrue(result.containsKey("issues"), "Should have 'issues' key");
        assertTrue(result.containsKey("sampleResponse"), "Should have 'sampleResponse' key");
    }

    @Test
    void testBuildOrchestratorPromptIncludesMarketStructure() {
        Long userId = 100L;
        List<CopilotSkill> skills = Arrays.asList(triangleSkill);
        CopilotInvestigation inv = CopilotInvestigation.builder()
                .id(1L)
                .layoutId(10L)
                .userId(100L)
                .symbol("NIFTY")
                .timeframesActive("1h,4h")
                .marketStructureData("Uptrend with higher highs")
                .build();

        when(orchestratorConfigRepository.findByUserId(userId))
                .thenReturn(Optional.empty());

        String prompt = orchestratorService.buildOrchestratorPrompt(userId, skills, inv, Optional.empty());

        assertTrue(prompt.contains("MARKET STRUCTURE"), "Should include market structure section");
        assertTrue(prompt.contains("Uptrend with higher highs"), "Should include market structure data");
    }

    @Test
    void testBuildOrchestratorPromptIncludesZigzagData() {
        Long userId = 100L;
        List<CopilotSkill> skills = Arrays.asList(triangleSkill);
        CopilotInvestigation inv = CopilotInvestigation.builder()
                .id(1L)
                .layoutId(10L)
                .userId(100L)
                .symbol("NIFTY")
                .timeframesActive("1h,4h")
                .zigzagData("Pivot at 22100, 22300, 22150")
                .build();

        when(orchestratorConfigRepository.findByUserId(userId))
                .thenReturn(Optional.empty());

        String prompt = orchestratorService.buildOrchestratorPrompt(userId, skills, inv, Optional.empty());

        assertTrue(prompt.contains("ZIGZAG PIVOTS"), "Should include zigzag section");
        assertTrue(prompt.contains("Pivot at"), "Should include zigzag data");
    }
}
