package com.dtech.kitecon.web.settings;

import com.dtech.kitecon.auth.User;
import com.dtech.kitecon.auth.UserRepository;
import com.dtech.kitecon.service.copilot.UserAiProviderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/settings/ai-providers")
@RequiredArgsConstructor
public class AiProviderController {

    private final UserAiProviderService providerService;
    private final UserRepository userRepository;

    record ProviderRequest(String name, String baseUrl, String model,
                            String apiKey, boolean localProvider, boolean makeActive) {}

    @GetMapping
    public ResponseEntity<List<UserAiProviderService.ProviderDto>> list(Authentication auth) {
        Long userId = resolveUserId(auth);
        return ResponseEntity.ok(providerService.listProviderDtos(userId));
    }

    @PostMapping
    public ResponseEntity<?> create(Authentication auth, @RequestBody ProviderRequest req) {
        Long userId = resolveUserId(auth);
        if (!req.localProvider() && (req.apiKey() == null || req.apiKey().isBlank())) {
            return ResponseEntity.badRequest().body(Map.of("error", "apiKey is required for non-local providers"));
        }
        if (req.name() == null || req.name().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }
        var created = providerService.createProvider(userId, req.name(), req.baseUrl(), req.model(),
                req.apiKey(), req.localProvider(), req.makeActive());
        return ResponseEntity.ok(toDto(created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(Authentication auth, @PathVariable Long id,
                                     @RequestBody ProviderRequest req) {
        Long userId = resolveUserId(auth);
        try {
            var updated = providerService.updateProvider(userId, id, req.name(), req.baseUrl(),
                    req.model(), req.apiKey(), req.localProvider());
            return ResponseEntity.ok(toDto(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(Authentication auth, @PathVariable Long id) {
        Long userId = resolveUserId(auth);
        try {
            providerService.deleteProvider(userId, id);
            return ResponseEntity.ok(Map.of("status", "deleted"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<?> activate(Authentication auth, @PathVariable Long id) {
        Long userId = resolveUserId(auth);
        try {
            var activated = providerService.setActive(userId, id);
            return ResponseEntity.ok(toDto(activated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    private Map<String, Object> toDto(com.dtech.kitecon.data.copilot.UserAiProvider p) {
        return Map.of(
                "id", p.getId(),
                "name", p.getName(),
                "baseUrl", p.getBaseUrl(),
                "model", p.getModel(),
                "localProvider", p.isLocalProvider(),
                "active", p.isActive(),
                "hasApiKey", p.getApiKeyEncrypted() != null && !p.getApiKeyEncrypted().isBlank()
        );
    }

    private Long resolveUserId(Authentication auth) {
        return userRepository.findByUsername(auth.getName())
                .map(User::getId)
                .orElseThrow(() -> new IllegalStateException("User not found"));
    }
}
