package com.dtech.kitecon.web.copilot;

import com.dtech.kitecon.auth.User;
import com.dtech.kitecon.auth.UserRepository;
import com.dtech.kitecon.service.copilot.UserOpenAiCredentialService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Allows users to save their own OpenAI API key via the UI.
 * Keys are stored encrypted per user — never in application.properties.
 */
@RestController
@RequestMapping("/api/copilot/credentials")
@RequiredArgsConstructor
public class UserOpenAiCredentialController {

    private final UserOpenAiCredentialService credentialService;
    private final UserRepository userRepository;

    /** Save or update the user's OpenAI API key */
    @PostMapping
    public ResponseEntity<?> saveCredential(Authentication auth,
                                             @RequestBody Map<String, String> body) {
        Long userId = resolveUserId(auth);
        String apiKey = body.get("apiKey");
        String model = body.getOrDefault("model", "gpt-4o-mini");
        String baseUrl = body.getOrDefault("baseUrl", "https://api.openai.com/v1");

        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "apiKey is required"));
        }
        credentialService.saveCredential(userId, apiKey, model, baseUrl);
        return ResponseEntity.ok(Map.of("status", "saved", "model", model, "baseUrl", baseUrl));
    }

    /** Check if user has configured their API key (returns masked status — never the key itself) */
    @GetMapping("/status")
    public ResponseEntity<?> getStatus(Authentication auth) {
        Long userId = resolveUserId(auth);
        boolean hasKey = credentialService.hasCredential(userId);
        if (hasKey) {
            String model = credentialService.getModel(userId);
            String baseUrl = credentialService.getBaseUrl(userId);
            return ResponseEntity.ok(Map.of("configured", true, "model", model, "baseUrl", baseUrl));
        }
        return ResponseEntity.ok(Map.of("configured", false));
    }

    /** Remove the user's API key */
    @DeleteMapping
    public ResponseEntity<?> deleteCredential(Authentication auth) {
        Long userId = resolveUserId(auth);
        credentialService.deleteCredential(userId);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    private Long resolveUserId(Authentication auth) {
        return userRepository.findByUsername(auth.getName())
                .map(User::getId)
                .orElseThrow(() -> new IllegalStateException("User not found: " + auth.getName()));
    }
}
