package com.dtech.kitecon.service.copilot;

import com.dtech.kitecon.data.copilot.CopilotInvestigation;
import com.dtech.kitecon.data.copilot.CopilotOrchestratorConfig;
import com.dtech.kitecon.data.copilot.CopilotSkill;
import com.dtech.kitecon.repository.copilot.CopilotOrchestratorConfigRepository;
import com.dtech.kitecon.service.copilot.dto.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Orchestrates the multi-turn AI investigation loop.
 *
 * Flow:
 * 1. App calls orchestrator AI with investigation context + list of available skills
 * 2. Orchestrator returns skill(s) to invoke (OrchestratorResponse)
 * 3. App checks cycle detection — refuses to re-invoke already-used skills
 * 4. App loads skill content, calls AI with skill + investigation context
 * 5. AI returns one of the six response types
 * 6. App routes response (updates investigation, creates hypothesis, flags anomaly, etc.)
 * 7. Loop continues until FINDING/ENTRY_SIGNAL/INVALIDATED or orchestrator says complete
 *
 * Key design principle: App acts. AI declares. AI never fetches data or updates state.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CopilotOrchestratorService {

    private final CopilotSkillService skillService;
    private final CopilotInvestigationService investigationService;
    private final CopilotAIService aiService;
    private final AIResponseParser responseParser;
    private final ObjectMapper objectMapper;
    private final CopilotOrchestratorConfigRepository orchestratorConfigRepository;

    private static final int MAX_TURNS = 20; // Safety guard against runaway loops

    // ─── Default instructions (hardcoded fallback) ────────────────────────────

    /**
     * The hardcoded default orchestrator instructions.
     * Used as fallback when the user has not customised their orchestrator.
     * Also shown as "Reset to default" reference text in the UI.
     */
    public String getDefaultInstructions() {
        return """
            You are a trading analysis orchestrator. Your ONLY job is to decide which skills to invoke
            and in what sequence based on the investigation context provided.
            You contain NO trading knowledge — all trading knowledge lives in the skills.

            You must return a JSON response with this exact structure:
            {
              "type": "ORCHESTRATOR",
              "skillsToInvoke": ["skill_key_1", "skill_key_2"],
              "selectionRationale": "brief explanation",
              "analysisComplete": false,
              "completionSummary": null
            }

            Rules:
            - Only select skills from the AVAILABLE SKILLS list below
            - Never select skills already in ALREADY INVOKED (cycle prevention)
            - If no more skills are needed, set analysisComplete=true and provide completionSummary
            - Prioritise PATTERN skills first, then WAVE skills for cross-verification
            - CONFIRMATION and OVERRIDE skills are invoked during monitoring phase only
            """;
    }

    // ─── User-specific instructions (DB-backed) ───────────────────────────────

    /**
     * Returns the user's custom orchestrator instructions, or the hardcoded default.
     */
    public String getInstructionsForUser(Long userId) {
        return orchestratorConfigRepository.findByUserId(userId)
                .map(CopilotOrchestratorConfig::getInstructions)
                .orElseGet(this::getDefaultInstructions);
    }

    /**
     * Returns whether the user has a custom orchestrator config.
     */
    public boolean isCustomized(Long userId) {
        return orchestratorConfigRepository.findByUserId(userId).isPresent();
    }

    /**
     * Save (upsert) the user's orchestrator instructions.
     */
    @Transactional
    public CopilotOrchestratorConfig saveInstructionsForUser(Long userId, String instructions) {
        CopilotOrchestratorConfig config = orchestratorConfigRepository.findByUserId(userId)
                .orElse(CopilotOrchestratorConfig.builder().userId(userId).build());
        config.setInstructions(instructions);
        return orchestratorConfigRepository.save(config);
    }

    /**
     * Delete the user's custom instructions, reverting to the hardcoded default.
     */
    @Transactional
    public void resetToDefault(Long userId) {
        orchestratorConfigRepository.findByUserId(userId)
                .ifPresent(orchestratorConfigRepository::delete);
    }

    /**
     * Validate proposed orchestrator instructions by running the AI against a dummy context
     * and verifying the response matches the expected ORCHESTRATOR JSON schema.
     *
     * Returns a map with: valid (boolean), issues (list<string>), sampleResponse (string)
     */
    public Map<String, Object> validateInstructions(Long userId, String instructions) {
        String dummyContext = instructions + "\n\n" +
                "=== AVAILABLE SKILLS ===\n" +
                "- triangle (key: triangle, category: PATTERN): Triangle pattern detection\n" +
                "- wave_4 (key: wave_4, category: WAVE): Elliott Wave 4 analysis\n\n" +
                "=== ALREADY INVOKED THIS SESSION ===\n" +
                "[]\n\n" +
                "=== CURRENT INVESTIGATION CONTEXT ===\n" +
                "Symbol: NIFTY\n" +
                "Timeframes: daily,1h\n" +
                "Wave count confirmed: false\n";

        List<String> issues = new ArrayList<>();
        String sampleResponse = null;
        boolean valid = false;

        try {
            String rawResponse = aiService.call(userId, dummyContext,
                    "Determine which skills to invoke for this investigation.");

            // Strip markdown fences
            String json = rawResponse.trim();
            if (json.startsWith("```")) {
                int start = json.indexOf('\n');
                int end = json.lastIndexOf("```");
                if (start > 0 && end > start) json = json.substring(start + 1, end).trim();
            }

            sampleResponse = json;

            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});

            // Check required fields
            Object type = parsed.get("type");
            if (!"ORCHESTRATOR".equals(type)) {
                issues.add("Response 'type' must be \"ORCHESTRATOR\" but got: " + type);
            }
            Object skillsToInvoke = parsed.get("skillsToInvoke");
            if (!(skillsToInvoke instanceof List)) {
                issues.add("'skillsToInvoke' must be a JSON array, got: " + skillsToInvoke);
            }
            Object rationale = parsed.get("selectionRationale");
            if (!(rationale instanceof String)) {
                issues.add("'selectionRationale' must be a string, got: " + rationale);
            }
            Object complete = parsed.get("analysisComplete");
            if (!(complete instanceof Boolean)) {
                issues.add("'analysisComplete' must be a boolean, got: " + complete);
            }

            valid = issues.isEmpty();

        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            issues.add("AI response is not valid JSON: " + e.getMessage());
        } catch (Exception e) {
            issues.add("Validation failed: " + e.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("valid", valid);
        result.put("issues", issues);
        result.put("sampleResponse", sampleResponse);
        return result;
    }

    // ─── Deprecated alias kept for backward-compat with any callers ──────────

    /** @deprecated Use getDefaultInstructions() or getInstructionsForUser(userId). */
    @Deprecated
    public String getStaticOrchestratorInstructions() {
        return getDefaultInstructions();
    }

    // ─── Prompt building ──────────────────────────────────────────────────────

    /**
     * Build the orchestrator system prompt for a specific user.
     * Uses the user's custom instructions if present, otherwise the hardcoded default.
     */
    public String buildOrchestratorPrompt(Long userId,
                                           List<CopilotSkill> availableSkills,
                                           CopilotInvestigation investigation,
                                           Optional<CopilotInvestigation> previousInvestigation) {
        StringBuilder sb = new StringBuilder();
        sb.append(getInstructionsForUser(userId)).append("\n");

        sb.append("=== AVAILABLE SKILLS ===\n");
        for (CopilotSkill skill : availableSkills) {
            sb.append(String.format("- %s (key: %s, category: %s): %s\n",
                    skill.getName(), skill.getSkillKey(), skill.getCategory(), skill.getDescription()));
        }

        sb.append("\n=== ALREADY INVOKED THIS SESSION ===\n");
        sb.append(investigation.getInvokedSkills()).append("\n");

        sb.append("\n=== CURRENT INVESTIGATION CONTEXT ===\n");
        sb.append("Symbol: ").append(investigation.getSymbol()).append("\n");
        sb.append("Timeframes: ").append(investigation.getTimeframesActive()).append("\n");
        sb.append("Wave count confirmed: ").append(investigation.getWaveCountConfirmed()).append("\n");
        if (investigation.getMarketStructureData() != null && !investigation.getMarketStructureData().isBlank()) {
            sb.append("\n--- MARKET STRUCTURE ---\n");
            sb.append(investigation.getMarketStructureData()).append("\n");
        }
        if (investigation.getZigzagData() != null && !investigation.getZigzagData().isBlank()) {
            sb.append("\n--- ZIGZAG PIVOTS ---\n");
            sb.append(investigation.getZigzagData()).append("\n");
        }
        if (investigation.getDrawingsData() != null && !investigation.getDrawingsData().isBlank()) {
            sb.append("Expert drawings: available\n");
        }

        if (previousInvestigation.isPresent()) {
            sb.append("\n=== PREVIOUS INVESTIGATION SUMMARY ===\n");
            sb.append("A prior investigation existed for this layout. Skill results summary:\n");
            sb.append(previousInvestigation.get().getSkillResults()).append("\n");
        }

        return sb.toString();
    }

    /**
     * Build a skill invocation prompt combining skill content + investigation context.
     */
    public String buildSkillPrompt(String skillPrompt, CopilotInvestigation investigation) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
            You are a specialised trading skill executing analysis on the provided market data.
            Apply the skill rules strictly. Do NOT introduce rules not in your skill definition.

            You must return a JSON response matching exactly ONE of these types:
            NEEDS_DATA | NEEDS_EXPERT | FINDING | ENTRY_SIGNAL | MONITORING | INVALIDATED

            The response structure depends on the type — use the correct fields for the type you return.
            Always include a "reasoning" field explaining your conclusion.

            """);

        sb.append(skillPrompt).append("\n\n");

        sb.append("=== INVESTIGATION CONTEXT ===\n");
        sb.append("Symbol: ").append(investigation.getSymbol()).append("\n");
        sb.append("Timeframes: ").append(investigation.getTimeframesActive()).append("\n");
        sb.append("Wave count confirmed: ").append(investigation.getWaveCountConfirmed()).append("\n");

        if (investigation.getMarketStructureData() != null && !investigation.getMarketStructureData().isBlank()) {
            sb.append("\n--- MARKET STRUCTURE ---\n");
            sb.append(investigation.getMarketStructureData()).append("\n");
        }

        if (investigation.getDrawingsData() != null && !investigation.getDrawingsData().isBlank()) {
            sb.append("\n--- EXPERT DRAWINGS ---\n");
            sb.append(investigation.getDrawingsData()).append("\n");
        }

        if (investigation.getZigzagData() != null && !investigation.getZigzagData().isBlank()) {
            sb.append("\n--- ZIGZAG PIVOTS ---\n");
            sb.append(investigation.getZigzagData()).append("\n");
        }

        String skillResults = investigation.getSkillResults();
        if (skillResults != null && !skillResults.isBlank() && !skillResults.equals("{}") && !skillResults.equals("[]")) {
            sb.append("\n--- PREVIOUS SKILL RESULTS THIS SESSION ---\n");
            sb.append(skillResults).append("\n");
        }

        return sb.toString();
    }

    // ─── Phase 1: Scan prompts ─────────────────────────────────────────────────

    /**
     * Build the system prompt for a Phase 1 scan skill invocation.
     * Instructs AI to return OBSERVATION type — detect only, no trade proposals.
     */
    public String buildScanPrompt(String skillPrompt, CopilotInvestigation investigation) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
            You are a specialised trading skill executing in SCAN MODE.
            Your ONLY job is to observe and report. Do NOT propose trades or hypotheses.

            SCAN FOR BOTH:
            1. Patterns currently FORMING (still developing, not yet complete)
            2. Patterns RECENTLY COMPLETED that may still have an active trade
               (e.g., a double bottom that already broke the neckline — the pattern is confirmed
               and a long trade may be running. Report it with the appropriate stage.)

            Scan the ENTIRE pivot history provided, not just the most recent few pivots.
            Patterns may have started forming 10-20+ pivots ago but are still relevant.

            Return a JSON response with type "OBSERVATION":
            {
              "type": "OBSERVATION",
              "patternDetected": true/false,
              "patternType": "triangle",
              "confidence": "HIGH" | "MEDIUM" | "LOW",
              "structuralDetails": "description of what was observed",
              "stage": "current stage or null",
              "keyLevels": [{"price": 22450, "label": "E point projection"}],
              "drawingPoints": [{"time": 1711234567, "price": 22100, "label": "A"}, ...],
              "drawingType": "triangle_pattern",
              "timeframe": "1h",
              "contradictions": ["list of contradictions found"],
              "reasoning": "brief explanation"
            }

            IMPORTANT:
            - drawingPoints must be chronological time+price coordinates for drawing the pattern on chart
            - Use UNIX TIMESTAMPS in seconds (epoch seconds) for the "time" field — take them directly
              from the pivot timestamps provided in the data
            - drawingType must be one of: triangle_pattern, elliott_impulse_wave, elliott_correction,
              elliott_triangle_wave, head_and_shoulders, parallel_channel, fib_retracement,
              xabcd_pattern, trend_line
            - If pattern NOT present, return patternDetected=false with empty drawingPoints
            - Do NOT suggest entries, SL, TP, or trade setups
            - Report contradictions honestly

            """);

        sb.append(skillPrompt).append("\n\n");

        appendInvestigationContext(sb, investigation);

        return sb.toString();
    }

    // ─── Phase 2: Reasoning prompts ──────────────────────────────────────────

    /**
     * Build the prompt for a Phase 2 reasoning skill invocation.
     * Input is observations summary, not raw market data.
     */
    public String buildReasoningPrompt(String skillPrompt, CopilotInvestigation investigation,
                                        String observationsSummary, String drawingsJson,
                                        String scenarioText, String priorHypothesesJson) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
            You are a specialised trading skill executing analysis on scan observations.
            Your job is to cross-correlate observations and determine if a tradeable setup exists.

            You must return a JSON response matching exactly ONE of these types:
            FINDING | NEEDS_EXPERT | INVALIDATED

            Always include a "reasoning" field explaining your conclusion.

            """);

        sb.append(skillPrompt).append("\n\n");

        if (observationsSummary != null && !observationsSummary.isBlank()) {
            sb.append(observationsSummary).append("\n");
        }

        if (drawingsJson != null && !drawingsJson.isBlank()) {
            sb.append("=== EXPERT DRAWINGS ===\n");
            sb.append(drawingsJson).append("\n\n");
        }

        if (scenarioText != null && !scenarioText.isBlank()) {
            sb.append("=== USER SCENARIO ===\n");
            sb.append(scenarioText).append("\n\n");
        }

        if (priorHypothesesJson != null && !priorHypothesesJson.isBlank()) {
            sb.append("=== PRIOR HYPOTHESES ===\n");
            sb.append(priorHypothesesJson).append("\n\n");
        }

        sb.append("=== INVESTIGATION CONTEXT ===\n");
        sb.append("Symbol: ").append(investigation.getSymbol()).append("\n");
        sb.append("Timeframes: ").append(investigation.getTimeframesActive()).append("\n");

        return sb.toString();
    }

    /**
     * Build the orchestrator prompt for Phase 2 — selects which reasoning skills to invoke.
     */
    public String buildReasoningOrchestratorPrompt(Long userId,
                                                     List<CopilotSkill> reasoningSkills,
                                                     CopilotInvestigation investigation,
                                                     String observationsSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append(getInstructionsForUser(userId)).append("\n");

        sb.append("=== AVAILABLE REASONING SKILLS ===\n");
        for (CopilotSkill skill : reasoningSkills) {
            sb.append(String.format("- %s (key: %s): %s\n",
                    skill.getName(), skill.getSkillKey(), skill.getDescription()));
        }

        sb.append("\n").append(observationsSummary).append("\n");

        sb.append("=== INVESTIGATION CONTEXT ===\n");
        sb.append("Symbol: ").append(investigation.getSymbol()).append("\n");
        sb.append("Timeframes: ").append(investigation.getTimeframesActive()).append("\n");

        return sb.toString();
    }

    // ─── Shared helpers ──────────────────────────────────────────────────────

    private void appendInvestigationContext(StringBuilder sb, CopilotInvestigation investigation) {
        sb.append("=== INVESTIGATION CONTEXT ===\n");
        sb.append("Symbol: ").append(investigation.getSymbol()).append("\n");
        sb.append("Timeframes: ").append(investigation.getTimeframesActive()).append("\n");
        sb.append("Wave count confirmed: ").append(investigation.getWaveCountConfirmed()).append("\n");

        if (investigation.getMarketStructureData() != null && !investigation.getMarketStructureData().isBlank()) {
            sb.append("\n--- MARKET STRUCTURE ---\n");
            sb.append(investigation.getMarketStructureData()).append("\n");
        }

        if (investigation.getDrawingsData() != null && !investigation.getDrawingsData().isBlank()) {
            sb.append("\n--- EXPERT DRAWINGS ---\n");
            sb.append(investigation.getDrawingsData()).append("\n");
        }

        if (investigation.getZigzagData() != null && !investigation.getZigzagData().isBlank()) {
            sb.append("\n--- ZIGZAG PIVOTS ---\n");
            sb.append(investigation.getZigzagData()).append("\n");
        }
    }

    // ─── Cycle detection ──────────────────────────────────────────────────────

    public boolean isSkillAlreadyInvoked(CopilotInvestigation investigation, String skillKey) {
        String invoked = investigation.getInvokedSkills();
        if (invoked == null || invoked.equals("[]")) return false;
        return invoked.contains("\"" + skillKey + "\"");
    }

    public List<String> filterCycleSkills(List<String> requested, CopilotInvestigation investigation) {
        if (requested == null) return List.of();
        return requested.stream()
                .filter(key -> !isSkillAlreadyInvoked(investigation, key))
                .toList();
    }

    // ─── Utilities ────────────────────────────────────────────────────────────

    public String buildInvestigationSummary(CopilotInvestigation investigation) {
        return String.format(
                "Investigation for %s | Timeframes: %s | Wave confirmed: %s | Skills run: %s",
                investigation.getSymbol(),
                investigation.getTimeframesActive(),
                investigation.getWaveCountConfirmed(),
                investigation.getInvokedSkills()
        );
    }

    public List<String> getInvokedSkillKeys(CopilotInvestigation investigation) {
        String json = investigation.getInvokedSkills();
        if (json == null || json.equals("[]")) return new ArrayList<>();
        try {
            String[] keys = objectMapper.readValue(json, String[].class);
            return new ArrayList<>(Arrays.asList(keys));
        } catch (Exception e) {
            log.warn("Failed to parse invoked skills: {}", json);
            return new ArrayList<>();
        }
    }
}
