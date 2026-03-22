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

@RestController
@RequestMapping("/api/copilot/skills")
@RequiredArgsConstructor
public class CopilotSkillController {

    private final CopilotSkillService skillService;
    private final CopilotOrchestratorService orchestratorService;
    private final CopilotAIService aiService;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<List<CopilotSkill>> getAllSkills(Authentication auth) {
        return ResponseEntity.ok(skillService.getAllSkillsForUser(resolveUserId(auth)));
    }

    @GetMapping("/category/{category}")
    public ResponseEntity<List<CopilotSkill>> getByCategory(Authentication auth,
                                                              @PathVariable String category) {
        return ResponseEntity.ok(skillService.getSkillsByCategory(resolveUserId(auth), category));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CopilotSkill> getSkill(@PathVariable Long id) {
        return skillService.getSkillById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<CopilotSkill> createSkill(Authentication auth,
                                                      @RequestBody CopilotSkill skill) {
        skill.setUserId(resolveUserId(auth));
        skill.setIsSystemSeed(false);
        skill.setIsActive(true);
        return ResponseEntity.ok(skillService.saveSkill(skill));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CopilotSkill> updateSkill(@PathVariable Long id,
                                                     @RequestBody CopilotSkill skill) {
        return skillService.getSkillById(id)
                .map(existing -> {
                    skill.setId(id);
                    skill.setUserId(existing.getUserId());
                    skill.setIsActive(existing.getIsActive() != null ? existing.getIsActive() : true);
                    skill.setIsSystemSeed(existing.getIsSystemSeed());
                    return ResponseEntity.ok(skillService.saveSkill(skill));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteSkill(@PathVariable Long id) {
        skillService.deleteSkill(id);
        return ResponseEntity.ok(Map.of("status", "deactivated"));
    }

    /** Seed demo skills (Triangle + Wave 4) for the current user */
    @PostMapping("/seed")
    public ResponseEntity<?> seedDemoSkills(Authentication auth) {
        skillService.seedDemoSkillsForUser(resolveUserId(auth));
        return ResponseEntity.ok(Map.of("status", "seeded", "skills", List.of("triangle", "wave_4")));
    }

    /** Get a full prompt-ready representation of a skill (for preview in Skill Builder UI) */
    @GetMapping("/{id}/prompt")
    public ResponseEntity<Map<String, String>> getSkillPrompt(@PathVariable Long id) {
        return skillService.getSkillById(id)
                .map(skill -> ResponseEntity.ok(Map.of("prompt", skillService.buildSkillPrompt(skill))))
                .orElse(ResponseEntity.notFound().build());
    }

    /** Returns the user's orchestrator instructions (custom or default). */
    @GetMapping("/orchestrator-prompt")
    public ResponseEntity<Map<String, String>> getOrchestratorPrompt(Authentication auth) {
        Long userId = resolveUserId(auth);
        return ResponseEntity.ok(Map.of("prompt", orchestratorService.getInstructionsForUser(userId)));
    }

    /** AI skill builder assistant — takes a user message + current skill state, returns reply + suggested field values. */
    @PostMapping("/ai-assist")
    public ResponseEntity<Map<String, Object>> aiAssistSkill(
            Authentication auth,
            @RequestBody Map<String, Object> body) {
        Long userId = resolveUserId(auth);
        String message = (String) body.getOrDefault("message", "");

        @SuppressWarnings("unchecked")
        Map<String, Object> currentSkill = (Map<String, Object>) body.getOrDefault("currentSkill", Map.of());

        String systemPrompt = """
                You are a trading skill builder assistant for an Elliott Wave analysis system.
                A skill has 7 fields:
                  1. identificationRules — how to spot the pattern/wave on the chart
                  2. stageDetection — how to determine the current stage within the pattern
                  3. entryRules — anticipatory and confirmation entries with SL/TP logic
                  4. indicatorRules — what MACD, RSI, Stochastic must show at each stage
                  5. invalidationRules — specific price action that kills this setup
                  6. ambiguityQuestions — targeted questions to ask the expert when unsure
                  7. crossVerificationRules — what must be true on OTHER timeframes

                When the user describes what they want, write detailed, specific content for the relevant field(s).
                IMPORTANT: Your suggestedFields will be AUTOMATICALLY applied to the skill fields immediately.
                So in your reply, say "I've written/updated [field names] for you" — never say "please click" or "paste this".
                Always populate suggestedFields with the actual content whenever you write any field text.
                Return ONLY a JSON object with no markdown fences:
                {
                  "reply": "brief confirmation of what fields you wrote and why",
                  "suggestedFields": {
                    "fieldName": "full detailed text for this field"
                  }
                }
                Field names must exactly match: identificationRules, stageDetection, entryRules,
                indicatorRules, invalidationRules, ambiguityQuestions, crossVerificationRules.
                suggestedFields may be empty only if the user is asking a question, not requesting content.
                """;

        StringBuilder userMessage = new StringBuilder();
        if (!currentSkill.isEmpty()) {
            userMessage.append("Current skill state:\n");
            currentSkill.forEach((k, v) -> {
                if (v != null && !v.toString().isBlank()) {
                    userMessage.append(k).append(": ").append(v).append("\n");
                }
            });
            userMessage.append("\n");
        }
        userMessage.append("User: ").append(message);

        try {
            String rawResponse = aiService.call(userId, systemPrompt, userMessage.toString());
            // Strip markdown fences if present
            String json = rawResponse.trim();
            if (json.startsWith("```")) {
                int start = json.indexOf('\n');
                int end = json.lastIndexOf("```");
                if (start > 0 && end > start) json = json.substring(start + 1, end).trim();
            }
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});
            return ResponseEntity.ok(parsed);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("reply", "Sorry, I couldn't process that. Please try again.", "suggestedFields", Map.of()));
        }
    }

    /**
     * Test a skill against a real or hypothetical chart scenario.
     *
     * Body: { symbol, timeframe, patternPresent, description }
     * Returns: { matched, verdict, analysis, failedRules[], suggestedChanges{} }
     */
    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> testSkill(
            Authentication auth,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {

        Long userId = resolveUserId(auth);
        CopilotSkill skill = skillService.getSkillById(id).orElse(null);
        if (skill == null) return ResponseEntity.notFound().build();

        String skillPrompt = skillService.buildSkillPrompt(skill);
        String symbol = (String) body.getOrDefault("symbol", "");
        String timeframe = (String) body.getOrDefault("timeframe", "");
        boolean patternPresent = Boolean.parseBoolean(body.getOrDefault("patternPresent", "true").toString());
        String description = (String) body.getOrDefault("description", "");

        String systemPrompt = """
                You are a strict validator for a trading skill used in an Elliott Wave analysis system.

                A skill is a set of rules an AI analyst uses to identify chart patterns, detect stages,
                and make trade decisions. You will evaluate whether this skill would have correctly handled
                a given real-world chart scenario.

                You will receive:
                  1. The compiled skill prompt (all 7 rule fields)
                  2. A chart context: symbol, timeframe, and a description of what was visible
                  3. Whether the pattern was actually present on that chart (YES or NO)

                Your job: evaluate each rule field against the described chart and determine whether
                the skill would have CORRECTLY identified (or correctly ignored) the pattern.

                Return ONLY a valid JSON object with no markdown fences:
                {
                  "matched": true or false,
                  "verdict": "one-sentence summary of pass or fail",
                  "analysis": "thorough rule-by-rule analysis — what each rule says vs what the chart showed",
                  "failedRules": ["identificationRules", "stageDetection"],
                  "suggestedChanges": {
                    "fieldName": "improved text that would have handled this case correctly"
                  }
                }

                Field names must exactly match: identificationRules, stageDetection, entryRules,
                indicatorRules, invalidationRules, ambiguityQuestions, crossVerificationRules.
                Only include fields in suggestedChanges if they need improvement.
                If matched is true, suggestedChanges should be empty.
                """;

        String userMessage = String.format("""
                SKILL PROMPT:
                %s

                CHART CONTEXT:
                Symbol: %s
                Timeframe: %s
                Pattern actually present on this chart: %s

                WHAT WAS VISIBLE / HAPPENED:
                %s
                """, skillPrompt, symbol, timeframe, patternPresent ? "YES" : "NO", description);

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
                    "matched", false,
                    "verdict", "Test could not be completed: " + e.getMessage(),
                    "analysis", "",
                    "failedRules", List.of(),
                    "suggestedChanges", Map.of()
            ));
        }
    }

    private Long resolveUserId(Authentication auth) {
        return userRepository.findByUsername(auth.getName())
                .map(User::getId)
                .orElseThrow(() -> new IllegalStateException("User not found: " + auth.getName()));
    }
}
