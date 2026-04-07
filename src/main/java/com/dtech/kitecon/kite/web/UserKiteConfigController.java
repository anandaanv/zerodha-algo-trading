package com.dtech.kitecon.kite.web;

import com.dtech.algo.runner.candle.KiteTickerService;
import com.dtech.kitecon.kite.service.UserKiteConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
public class UserKiteConfigController {

    private final UserKiteConfigService service;
    private final KiteTickerService tickerService;

    @Value("${app.frontend.url:}")
    private String frontendUrl;

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
        return ResponseEntity.ok(service.update(id, req.getLabel(), req.getActive()));
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
     * Return JSON with Kite OAuth login URL.
     * The Kite developer console must have redirect URI set to:
     *   {server}/kite-callback/config/{id}
     */
    @GetMapping("/api/admin/kite-configs/{id}/connect")
    public ResponseEntity<?> connect(@PathVariable Long id) {
        String loginUrl = service.getLoginUrl(id);
        return ResponseEntity.ok(Map.of("url", loginUrl));
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
        return new RedirectView(frontendUrl + "/admin/kite-config");
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
    }
}
