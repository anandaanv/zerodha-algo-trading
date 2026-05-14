package com.dtech.kitecon.kite.web;

import com.dtech.algo.runner.candle.KiteTickerService;
import com.dtech.kitecon.config.KiteConnectPool;
import com.dtech.kitecon.kite.entity.UserKiteConfig;
import com.dtech.kitecon.kite.repository.UserKiteConfigRepository;
import com.dtech.kitecon.kite.service.KiteAutoLoginService;
import com.dtech.kitecon.kite.service.UserKiteConfigService;
import com.zerodhatech.kiteconnect.KiteConnect;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.time.Instant;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
public class UserKiteConfigController {

    private final UserKiteConfigService service;
    private final KiteTickerService tickerService;
    private final KiteAutoLoginService autoLoginService;
    private final UserKiteConfigRepository configRepository;
    private final KiteConnectPool kiteConnectPool;

    // ── CRUD endpoints (admin only — enforced by SecurityConfig /api/admin/**) ──

    @GetMapping("/api/admin/kite-configs")
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(service.listAll());
    }

    @PostMapping("/api/admin/kite-configs")
    public ResponseEntity<?> create(@RequestBody CreateRequest req) {
        return ResponseEntity.ok(service.create(
                req.getPlatformUserId(), req.getLabel(),
                req.getApiKey(), req.getApiSecret(), req.getKiteUserId()));
    }

    @PutMapping("/api/admin/kite-configs/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody UpdateRequest req) {
        return ResponseEntity.ok(service.update(
                id, req.getLabel(), req.getActive(),
                req.getApiKey(), req.getApiSecret(), req.getKiteUserId()));
    }

    @DeleteMapping("/api/admin/kite-configs/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    @PostMapping("/api/admin/kite-configs/{id}/disconnect")
    public ResponseEntity<?> disconnect(@PathVariable Long id) {
        service.disconnect(id);
        return ResponseEntity.ok(Map.of("status", "disconnected"));
    }

    /**
     * Redirect browser to Kite OAuth page for this config.
     * The Kite developer console must have redirect URI set to:
     *   {server}/kite-callback/config/{id}
     */
    @GetMapping("/api/admin/kite-configs/{id}/connect")
    public RedirectView connect(@PathVariable Long id) {
        log.info("[KiteLogin] connect called for config id={}", id);
        try {
            String loginUrl = service.getLoginUrl(id);
            log.info("[KiteLogin] redirecting to Kite URL: {}", loginUrl);
            return new RedirectView(loginUrl);
        } catch (Exception e) {
            log.error("[KiteLogin] failed to build login URL for id={}: {}", id, e.getMessage(), e);
            throw e;
        }
    }

    // ── OAuth callback (no JWT — redirected from Kite servers) ──

    @GetMapping("/kite-callback/config/{configId}")
    public RedirectView kiteCallback(
            @PathVariable Long configId,
            @RequestParam("request_token") String requestToken) {
        try {
            service.processCallback(configId, requestToken);
            tickerService.init();
            log.info("Kite config {} authenticated successfully", configId);
        } catch (Exception e) {
            log.error("Kite callback failed for config {}: {}", configId, e.getMessage(), e);
        }
        return new RedirectView("/admin/kite-config");
    }

    @PostMapping("/api/admin/kite-configs/{id}/process-token")
    public ResponseEntity<?> processToken(
            @PathVariable Long id,
            @RequestParam("request_token") String requestToken) {
        try {
            service.processCallback(id, requestToken);
            tickerService.init();
            log.info("Kite config {} authenticated via process-token", id);
            return ResponseEntity.ok(Map.of("status", "ok", "connected", true));
        } catch (Exception e) {
            log.error("process-token failed for config {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    // ── Auto-login endpoints ──

    /** Stores Zerodha login password + base32 TOTP secret (encrypted at rest). */
    @PatchMapping("/api/admin/kite-configs/{id}/auto-login-credentials")
    public ResponseEntity<?> setAutoLoginCredentials(
            @PathVariable Long id,
            @RequestBody AutoLoginCredentialsRequest req) {
        try {
            service.setAutoLoginCredentials(id, req.getPassword(), req.getTotpSecret());
            return ResponseEntity.ok(Map.of("status", "saved"));
        } catch (Exception e) {
            log.error("Failed to set auto-login credentials for config {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /** Manual trigger of the headless 5-step auto-login. Useful for testing. */
    @PostMapping("/api/admin/kite-configs/{id}/auto-login")
    public ResponseEntity<?> autoLogin(@PathVariable Long id) {
        UserKiteConfig cfg = configRepository.findById(id).orElse(null);
        if (cfg == null) return ResponseEntity.notFound().build();
        try {
            KiteAutoLoginService.LoginResult result = autoLoginService.login(cfg);
            cfg.setAccessToken(result.accessToken());
            cfg.setPublicToken(result.publicToken());
            cfg.setUpdatedAt(Instant.now());
            configRepository.save(cfg);
            // Refresh pool
            KiteConnect kc = new KiteConnect(cfg.getApiKey());
            if (cfg.getKiteUserId() != null) kc.setUserId(cfg.getKiteUserId());
            kc.setAccessToken(result.accessToken());
            if (result.publicToken() != null) kc.setPublicToken(result.publicToken());
            kiteConnectPool.reloadUserKiteConfig(cfg.getId(), kc);
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "configId", cfg.getId(),
                    "connected", true,
                    "updatedAt", cfg.getUpdatedAt().toString()));
        } catch (Exception e) {
            log.error("Auto-login failed for config {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    // ── Request DTOs ──

    @lombok.Data
    public static class CreateRequest {
        private Long platformUserId;
        private String label;
        private String apiKey;
        private String apiSecret;
        private String kiteUserId;
    }

    @lombok.Data
    public static class UpdateRequest {
        private String label;
        private Boolean active;
        // Optional — only updated when present + non-blank. Editing api_key or
        // api_secret evicts the cached pool entry so the next call reloads.
        private String apiKey;
        private String apiSecret;
        private String kiteUserId;
    }

    @lombok.Data
    public static class AutoLoginCredentialsRequest {
        private String password;
        private String totpSecret;
    }
}
