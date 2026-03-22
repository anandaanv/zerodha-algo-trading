package com.dtech.kitecon.web.copilot;

import com.dtech.kitecon.auth.User;
import com.dtech.kitecon.auth.UserRepository;
import com.dtech.kitecon.service.copilot.CopilotOrchestratorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

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

    private Long resolveUserId(Authentication auth) {
        return userRepository.findByUsername(auth.getName())
                .map(User::getId)
                .orElseThrow(() -> new IllegalStateException("User not found: " + auth.getName()));
    }
}
