package com.dtech.kitecon.service.copilot;

import com.dtech.kitecon.data.copilot.CopilotInvestigation;
import com.dtech.kitecon.data.copilot.CopilotSkill;
import com.dtech.kitecon.service.copilot.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

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
    private final AIResponseParser responseParser;
    private final ObjectMapper objectMapper;

    private static final int MAX_TURNS = 20; // Safety guard against runaway loops

    /**
     * Build the orchestrator system prompt.
     * The orchestrator knows what skills exist but contains no trading knowledge itself.
     */
    public String buildOrchestratorPrompt(List<CopilotSkill> availableSkills,
                                           CopilotInvestigation investigation,
                                           Optional<CopilotInvestigation> previousInvestigation) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
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

            """);

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
        if (investigation.getMarketStructureData() != null) {
            sb.append("Market structure data: available\n");
        }
        if (investigation.getDrawingsData() != null) {
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

    /**
     * Check if a skill key has already been invoked in this investigation.
     * Cycle detection — prevents re-invoking the same skill.
     */
    public boolean isSkillAlreadyInvoked(CopilotInvestigation investigation, String skillKey) {
        String invoked = investigation.getInvokedSkills();
        if (invoked == null || invoked.equals("[]")) return false;
        return invoked.contains("\"" + skillKey + "\"");
    }

    /**
     * Filter the orchestrator's requested skill list to remove already-invoked skills.
     * Returns only skills that are safe to call.
     */
    public List<String> filterCycleSkills(List<String> requested, CopilotInvestigation investigation) {
        if (requested == null) return List.of();
        return requested.stream()
                .filter(key -> !isSkillAlreadyInvoked(investigation, key))
                .toList();
    }

    /**
     * Build a summary of the investigation for chat display.
     */
    public String buildInvestigationSummary(CopilotInvestigation investigation) {
        return String.format(
                "Investigation for %s | Timeframes: %s | Wave confirmed: %s | Skills run: %s",
                investigation.getSymbol(),
                investigation.getTimeframesActive(),
                investigation.getWaveCountConfirmed(),
                investigation.getInvokedSkills()
        );
    }

    /**
     * Parse the invoked skills JSON array from the investigation.
     */
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
