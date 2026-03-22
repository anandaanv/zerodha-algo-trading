package com.dtech.kitecon.web.copilot;

import com.dtech.kitecon.auth.User;
import com.dtech.kitecon.auth.UserRepository;
import com.dtech.kitecon.data.copilot.CopilotSkill;
import com.dtech.kitecon.service.copilot.CopilotAIService;
import com.dtech.kitecon.service.copilot.CopilotOrchestratorService;
import com.dtech.kitecon.service.copilot.CopilotSkillService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST API for managing the user's orchestrator instructions.
 *
 * GET  /api/copilot/orchestrator         — get current instructions + metadata
 * GET  /api/copilot/orchestrator/default — get the hardcoded default (for Reset UI)
 * PUT  /api/copilot/orchestrator         — save custom instructions
 * POST /api/copilot/orchestrator/validate — validate instructions via AI dry-run
 * DELETE /api/copilot/orchestrator       — reset to default (delete customisation)
 */
@RestController
@RequestMapping("/api/copilot/orchestrator")
@RequiredArgsConstructor
public class CopilotOrchestratorController {

    private final CopilotOrchestratorService orchestratorService;
    private final CopilotSkillService skillService;
    private final CopilotAIService aiService;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;

    /** Get the user's current orchestrator instructions plus metadata. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getConfig(Authentication auth) {
        Long userId = resolveUserId(auth);
        return ResponseEntity.ok(Map.of(
                "instructions", orchestratorService.getInstructionsForUser(userId),
                "isCustomized", orchestratorService.isCustomized(userId)
        ));
    }

    /** Get the hardcoded default instructions (shown in Reset-to-default UI). */
    @GetMapping("/default")
    public ResponseEntity<Map<String, String>> getDefault() {
        return ResponseEntity.ok(Map.of("instructions", orchestratorService.getDefaultInstructions()));
    }

    /** Save custom orchestrator instructions for the user. */
    @PutMapping
    public ResponseEntity<Map<String, Object>> saveConfig(Authentication auth,
                                                           @RequestBody Map<String, String> body) {
        Long userId = resolveUserId(auth);
        String instructions = body.get("instructions");
        if (instructions == null || instructions.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "instructions must not be blank"));
        }
        orchestratorService.saveInstructionsForUser(userId, instructions);
        return ResponseEntity.ok(Map.of(
                "instructions", orchestratorService.getInstructionsForUser(userId),
                "isCustomized", true
        ));
    }

    /**
     * Validate proposed orchestrator instructions.
     * Calls the AI with a dummy context and checks the response matches the expected schema.
     * Returns: { valid, issues[], sampleResponse }
     */
    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validate(Authentication auth,
                                                         @RequestBody Map<String, String> body) {
        Long userId = resolveUserId(auth);
        String instructions = body.getOrDefault("instructions", "");
        return ResponseEntity.ok(orchestratorService.validateInstructions(userId, instructions));
    }

    /** Reset to the hardcoded default by deleting the user's custom config. */
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> resetToDefault(Authentication auth) {
        Long userId = resolveUserId(auth);
        orchestratorService.resetToDefault(userId);
        return ResponseEntity.ok(Map.of(
                "instructions", orchestratorService.getDefaultInstructions(),
                "isCustomized", false
        ));
    }

    /**
     * Test the orchestrator against a hypothetical market scenario.
     *
     * Body: { symbol, timeframe, description, expectedSkills[] }
     * Returns: { selectedSkills[], correct, verdict, analysis, suggestedChanges }
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testOrchestrator(
            Authentication auth,
            @RequestBody Map<String, Object> body) {

        Long userId = resolveUserId(auth);
        String instructions = orchestratorService.getInstructionsForUser(userId);
        List<CopilotSkill> activeSkills = skillService.getAllSkillsForUser(userId)
                .stream().filter(s -> Boolean.TRUE.equals(s.getIsActive())).collect(Collectors.toList());

        String symbol = (String) body.getOrDefault("symbol", "");
        String timeframe = (String) body.getOrDefault("timeframe", "");
        String description = (String) body.getOrDefault("description", "");

        @SuppressWarnings("unchecked")
        List<String> expectedSkills = (List<String>) body.getOrDefault("expectedSkills", List.of());

        String skillList = activeSkills.stream()
                .map(s -> "- " + s.getSkillKey() + ": " + s.getName()
                        + (s.getDescription() != null && !s.getDescription().isBlank()
                                ? " — " + s.getDescription() : ""))
                .collect(Collectors.joining("\n"));

        String systemPrompt = """
                You are validating an orchestrator's skill-selection logic for an Elliott Wave analysis system.

                Given a market context, you must determine which skills the orchestrator would select
                based on its instructions, then evaluate if those selections are correct.

                Return ONLY a valid JSON object with no markdown fences:
                {
                  "selectedSkills": ["skill_key1", "skill_key2"],
                  "correct": true or false,
                  "verdict": "one-sentence summary",
                  "analysis": "detailed reasoning — why each skill was or wasn't selected, and whether that was right",
                  "suggestedChanges": "improved orchestrator instruction text if the selection was wrong, or empty string if correct"
                }
                """;

        String userMessage = String.format("""
                ORCHESTRATOR INSTRUCTIONS:
                %s

                AVAILABLE ACTIVE SKILLS:
                %s

                CHART CONTEXT:
                Symbol: %s
                Timeframe: %s

                WHAT IS VISIBLE ON THE CHART / MARKET SITUATION:
                %s

                SKILLS THE USER EXPECTED TO BE SELECTED:
                %s
                """,
                instructions,
                skillList.isBlank() ? "(no active skills)" : skillList,
                symbol, timeframe, description,
                expectedSkills.isEmpty() ? "(user did not specify — just determine what the orchestrator would pick)" :
                        String.join(", ", expectedSkills));

        try {
            String raw = aiService.call(userId, systemPrompt, userMessage);
            String json = raw.trim();
            if (json.startsWith("```")) {
                int start = json.indexOf('\n');
                int end = json.lastIndexOf("```");
                if (start > 0 && end > start) json = json.substring(start + 1, end).trim();
            }
            Map<String, Object> result = objectMapper.readValue(json, new TypeReference<>() {});
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "selectedSkills", List.of(),
                    "correct", false,
                    "verdict", "Test could not be completed: " + e.getMessage(),
                    "analysis", "",
                    "suggestedChanges", ""
            ));
        }
    }

    private Long resolveUserId(Authentication auth) {
        return userRepository.findByUsername(auth.getName())
                .map(User::getId)
                .orElseThrow(() -> new IllegalStateException("User not found: " + auth.getName()));
    }
}
