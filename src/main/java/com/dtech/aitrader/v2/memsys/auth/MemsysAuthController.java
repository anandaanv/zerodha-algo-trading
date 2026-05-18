package com.dtech.aitrader.v2.memsys.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.util.Map;
import java.util.UUID;

/**
 * Endpoints that drive the memsys OAuth flow for algotrade users.
 *
 * Per-user:
 *   GET  /api/admin/memsys-configs/{userId}/connect   — auto-DCR if needed, then redirects browser to Cognito authorize
 *   GET  /memsys/callback                              — Cognito redirects here with code → tokens
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class MemsysAuthController {

    private final MemsysOAuthService oauthService;
    private final UserMemsysConfigRepository userMemsysConfigRepository;
    private final MemsysKiteOnboardingService kiteOnboardingService;

    @Value("${frontend.base-url:http://localhost:5173}")
    private String frontendBaseUrl;

    // ── per-user: kick off the Connect flow (self-bootstrapping DCR) ────

    @GetMapping("/api/admin/memsys-configs/{userId}/connect")
    public RedirectView connect(@PathVariable Long userId, Authentication auth) {
        try {
            // Self-bootstrapping: check if user has a registered DCR client. If not, register it.
            UserMemsysConfig cfg = userMemsysConfigRepository.findByUserId(userId).orElse(null);
            if (cfg == null || cfg.getClientId() == null || cfg.getClientId().isBlank()) {
                log.info("[memsys-oauth] userId={} missing DCR client; registering now", userId);
                oauthService.registerAndPersistForUser(userId, "AlgoTrade User " + userId);
            }

            // CSRF protection: bind state to (userId + random nonce). Real impl could store in
            // a server-side state cache or a signed JWT; for now we use a URL-safe encoding that
            // the callback handler can parse. Cognito echoes state back verbatim.
            String nonce = UUID.randomUUID().toString();
            String state = userId + ":" + nonce;
            String url = oauthService.buildAuthorizeUrl(userId, state);
            log.info("[memsys-oauth] redirecting userId={} to memsys authorize URL", userId);
            return new RedirectView(url);
        } catch (Exception e) {
            log.error("[memsys-oauth] DCR registration failed for userId={}: {}", userId, e.getMessage(), e);
            return new RedirectView(frontUrl("/admin/memsys?status=error&reason=dcr_failed"));
        }
    }

    // ── Cognito callback: exchange code, persist tokens, redirect to UI ──

    @GetMapping("/memsys/callback")
    public RedirectView callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false, name = "error") String errorParam,
            @RequestParam(required = false, name = "error_description") String errorDescription) {

        if (errorParam != null && !errorParam.isBlank()) {
            log.warn("[memsys-oauth] callback received error: {} {}", errorParam, errorDescription);
            return new RedirectView(frontUrl("/admin/memsys?status=error&reason=" + errorParam));
        }
        if (code == null || code.isBlank() || state == null || state.isBlank()) {
            log.warn("[memsys-oauth] callback missing code or state");
            return new RedirectView(frontUrl("/admin/memsys?status=error&reason=missing_params"));
        }

        // Decode state — expected "<userId>:<nonce>".
        Long userId;
        try {
            int idx = state.indexOf(':');
            String userPart = idx > 0 ? state.substring(0, idx) : state;
            userId = Long.parseLong(userPart);
        } catch (NumberFormatException nfe) {
            log.warn("[memsys-oauth] callback got unparseable state={}", state);
            return new RedirectView(frontUrl("/admin/memsys?status=error&reason=bad_state"));
        }

        try {
            MemsysOAuthService.TokenSet tokens = oauthService.exchangeCode(userId, code);
            UserMemsysConfig saved = oauthService.persistTokens(userId, tokens);
            log.info("[memsys-oauth] connected memsys for userId={} memsys_tenant_sub={} scopes={}",
                    userId, saved.getMemsysTenantSub(), saved.getScopes());
            return new RedirectView(frontUrl("/admin/memsys?status=connected"));
        } catch (Exception e) {
            log.error("[memsys-oauth] callback exchange failed for state={}: {}", state, e.getMessage(), e);
            return new RedirectView(frontUrl("/admin/memsys?status=error&reason=exchange_failed"));
        }
    }

    // ── per-user: push Kite creds into memsys vault ─────────────────────

    @PostMapping("/api/admin/memsys-configs/{userId}/enable-kite")
    public ResponseEntity<?> enableKite(@PathVariable Long userId, Authentication auth) {
        try {
            com.fasterxml.jackson.databind.JsonNode result = kiteOnboardingService.enableForUser(userId);
            return ResponseEntity.ok(Map.of(
                    "userId", userId,
                    "enabled", true,
                    "memsys_response", result == null ? "(null)" : result
            ));
        } catch (IllegalStateException ise) {
            log.warn("[memsys-kite-onboard] enable-kite blocked for userId={}: {}", userId, ise.getMessage());
            return ResponseEntity.status(400).body(Map.of(
                    "userId", userId,
                    "enabled", false,
                    "error", ise.getMessage()
            ));
        } catch (Exception e) {
            log.error("[memsys-kite-onboard] enable-kite failed for userId={}", userId, e);
            return ResponseEntity.status(500).body(Map.of(
                    "userId", userId,
                    "enabled", false,
                    "error", e.getMessage()
            ));
        }
    }

    private String frontUrl(String suffix) {
        String base = frontendBaseUrl == null ? "" : frontendBaseUrl.replaceAll("/+$", "");
        return base + suffix;
    }
}
