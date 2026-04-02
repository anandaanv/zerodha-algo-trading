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
 * Also supports local LLM endpoints (llama.cpp, Ollama, LM Studio, vLLM).
 */
@RestController
@RequestMapping("/api/copilot/credentials")
@RequiredArgsConstructor
public class UserOpenAiCredentialController {

    private final UserOpenAiCredentialService credentialService;
    private final UserRepository userRepository;

    record SaveCredentialRequest(String apiKey, String model, String baseUrl, boolean localProvider) {}

    /** Save or update the user's OpenAI API key or local LLM endpoint */
    @PostMapping
    public ResponseEntity<?> saveCredential(Authentication auth,
                                             @RequestBody SaveCredentialRequest request) {
        Long userId = resolveUserId(auth);
        String apiKey = request.apiKey();
        String model = request.model();
        String baseUrl = request.baseUrl();
        boolean localProvider = request.localProvider();

        // API key is required for OpenAI, optional for local providers
        if (!localProvider && (apiKey == null || apiKey.isBlank())) {
            return ResponseEntity.badRequest().body(Map.of("error", "apiKey is required for OpenAI"));
        }

        credentialService.saveCredential(userId, apiKey,
                model != null ? model : "gpt-4.1-mini",
                baseUrl != null ? baseUrl : "https://api.openai.com/v1",
                localProvider);
        return ResponseEntity.ok(Map.of("status", "saved", "model", model, "baseUrl", baseUrl, "localProvider", localProvider));
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
