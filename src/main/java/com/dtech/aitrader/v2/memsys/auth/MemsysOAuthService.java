package com.dtech.aitrader.v2.memsys.auth;

import com.dtech.kitecon.service.copilot.CopilotEncryptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * memsys OAuth flow + token lifecycle. Built on the two-tier DCR shipped by memsys on
 * 2026-05-18 (memsys memory 060dad62-bbbb-499b-8c6c-18886295108e).
 *
 * Lifecycle:
 *  1. Operator does one-time DCR registration → captures client_id + client_secret.
 *  2. User clicks "Connect memsys" → algotrade builds the authorize URL, browser redirects.
 *  3. Cognito-hosted Google sign-in → callback to /memsys/callback with code.
 *  4. exchangeCode() trades code for {access_token, refresh_token, id_token}.
 *  5. persistTokens() encrypts + stores in user_memsys_config.
 *  6. getValidAccessToken() returns a usable bearer; auto-refreshes when expired.
 *  7. refreshAccessToken() rotates RT, returns new access_token.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MemsysOAuthService {

    private static final long REFRESH_SLACK_SECONDS = 60; // refresh if expiring within 60s

    private final MemsysOAuthProperties props;
    private final UserMemsysConfigRepository repository;
    private final CopilotEncryptionService encryptionService;
    private final ObjectMapper mapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    // ── Step 1: one-time DCR registration → persist per-user ─────────────

    /**
     * Register a confidential OAuth client with memsys DCR, then upsert the user's
     * {@code user_memsys_config} row with the issued credentials.
     *
     * @param userId algotrade user id
     * @param clientName human-readable client name for DCR
     * @return persisted UserMemsysConfig with clientId + clientSecretEncrypted set
     */
    @Transactional
    public UserMemsysConfig registerAndPersistForUser(Long userId, String clientName) {
        // Call DCR to get client_id + client_secret
        DcrResponse dcr = performDcr(clientName);

        // Upsert user_memsys_config row
        UserMemsysConfig cfg = repository.findByUserId(userId)
                .orElseGet(() -> UserMemsysConfig.builder()
                        .userId(userId)
                        .active(true)
                        .build());

        if (cfg.getClientId() != null && !cfg.getClientId().isBlank()) {
            log.warn("[memsys-oauth] userId={} already has clientId set; overwriting with new DCR registration",
                    userId);
        }

        cfg.setClientId(dcr.clientId);
        cfg.setClientSecretEncrypted(encryptionService.encrypt(dcr.clientSecret));
        cfg.setActive(true);
        UserMemsysConfig saved = repository.save(cfg);

        log.info("[memsys-oauth] registered + persisted DCR client for userId={}; client_id={}",
                userId, dcr.clientId);
        return saved;
    }

    private DcrResponse performDcr(String clientName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_name", clientName);
        body.put("software_id", "algotrade");
        body.put("redirect_uris", java.util.List.of(props.getRedirectUri()));
        body.put("token_endpoint_auth_method", "client_secret_post");
        body.put("scope", "memory.read memory.write");
        body.put("grant_types", java.util.List.of("authorization_code", "refresh_token"));

        String bodyJson;
        try {
            bodyJson = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new MemsysOAuthException("failed to serialize DCR body", e);
        }

        HttpRequest req = HttpRequest.newBuilder(URI.create(props.getDcrUrl()))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build();

        HttpResponse<String> res = send(req);
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new MemsysOAuthException(
                    "DCR registration failed: HTTP " + res.statusCode() + " " + truncate(res.body(), 400));
        }
        try {
            JsonNode root = mapper.readTree(res.body());
            return new DcrResponse(
                    root.path("client_id").asText(null),
                    root.path("client_secret").asText(null),
                    root.path("redirect_uris").toString(),
                    root.path("scope").asText("")
            );
        } catch (Exception e) {
            throw new MemsysOAuthException("DCR registration returned malformed JSON: " + truncate(res.body(), 400), e);
        }
    }

    private record DcrResponse(String clientId, String clientSecret, String redirectUris, String scope) {}

    // ── Step 2: build the authorize URL the browser is redirected to ────

    /**
     * Build the authorize URL for the given user, using their per-user clientId.
     *
     * @param userId algotrade user id
     * @param state CSRF state token
     * @return authorize URL
     */
    public String buildAuthorizeUrl(Long userId, String state) {
        UserMemsysConfig cfg = ensureClientForUser(userId);
        StringBuilder sb = new StringBuilder(props.getAuthorizeUrl());
        sb.append("?response_type=code");
        sb.append("&client_id=").append(URLEncoder.encode(cfg.getClientId(), StandardCharsets.UTF_8));
        sb.append("&redirect_uri=").append(URLEncoder.encode(props.getRedirectUri(), StandardCharsets.UTF_8));
        sb.append("&scope=").append(URLEncoder.encode(props.getScopes(), StandardCharsets.UTF_8));
        sb.append("&state=").append(URLEncoder.encode(state, StandardCharsets.UTF_8));
        return sb.toString();
    }

    /**
     * Validate that the user has a registered memsys OAuth client.
     *
     * @param userId algotrade user id
     * @return the UserMemsysConfig with clientId + clientSecretEncrypted set
     * @throws MemsysOAuthException if client not registered
     */
    private UserMemsysConfig ensureClientForUser(Long userId) {
        UserMemsysConfig cfg = repository.findByUserId(userId)
                .orElseThrow(() -> new MemsysOAuthException(
                        "user " + userId + " has not registered a memsys OAuth client; "
                                + "call /api/admin/memsys-configs/{userId}/dcr-register first"));
        if (cfg.getClientId() == null || cfg.getClientId().isBlank()) {
            throw new MemsysOAuthException(
                    "user " + userId + " has not registered a memsys OAuth client; "
                            + "call /api/admin/memsys-configs/{userId}/dcr-register first");
        }
        return cfg;
    }

    // ── Step 3: exchange auth code for tokens ───────────────────────────

    /**
     * Exchange the authorization code for tokens.
     *
     * @param userId algotrade user id
     * @param code authorization code from Cognito callback
     * @return token set
     */
    public TokenSet exchangeCode(Long userId, String code) {
        UserMemsysConfig cfg = ensureClientForUser(userId);
        String clientSecret;
        try {
            clientSecret = encryptionService.decrypt(cfg.getClientSecretEncrypted());
        } catch (Exception e) {
            throw new MemsysOAuthException("failed to decrypt client_secret for userId=" + userId, e);
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("client_id", cfg.getClientId());
        form.put("client_secret", clientSecret);
        form.put("redirect_uri", props.getRedirectUri());

        return postToken(form);
    }

    // ── Step 4: persist tokens for an algotrade user ────────────────────

    @Transactional
    public UserMemsysConfig persistTokens(Long userId, TokenSet tokens) {
        UserMemsysConfig existing = repository.findByUserId(userId).orElse(null);
        UserMemsysConfig cfg = existing != null ? existing : UserMemsysConfig.builder()
                .userId(userId)
                .active(true)
                .build();
        cfg.setAccessTokenEncrypted(encryptionService.encrypt(tokens.accessToken()));
        cfg.setRefreshTokenEncrypted(encryptionService.encrypt(tokens.refreshToken()));
        cfg.setExpiresAt(tokens.expiresAt());
        cfg.setScopes(tokens.scope());
        cfg.setMemsysTenantSub(tokens.subject());
        cfg.setDisplayName(tokens.displayName());
        cfg.setActive(true);
        return repository.save(cfg);
    }

    // ── Step 5: get a valid access token (refresh on-the-fly if expiring) ──

    @Transactional
    public Optional<String> getValidAccessToken(Long userId) {
        Optional<UserMemsysConfig> opt = repository.findByUserIdAndActiveTrue(userId);
        if (opt.isEmpty()) return Optional.empty();
        UserMemsysConfig cfg = opt.get();

        if (cfg.getExpiresAt() == null
                || cfg.getExpiresAt().minusSeconds(REFRESH_SLACK_SECONDS).isBefore(Instant.now())) {
            log.info("[memsys-oauth] access token expiring (or expired) for userId={} — refreshing", userId);
            return refreshAndReturnAccessToken(cfg);
        }

        try {
            return Optional.of(encryptionService.decrypt(cfg.getAccessTokenEncrypted()));
        } catch (Exception e) {
            log.warn("[memsys-oauth] failed to decrypt access token for userId={}: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Forced refresh — used by retry-on-401 in the MCP client. */
    @Transactional
    public Optional<String> forceRefresh(Long userId) {
        Optional<UserMemsysConfig> opt = repository.findByUserIdAndActiveTrue(userId);
        if (opt.isEmpty()) return Optional.empty();
        return refreshAndReturnAccessToken(opt.get());
    }

    private Optional<String> refreshAndReturnAccessToken(UserMemsysConfig cfg) {
        String refreshToken;
        try {
            refreshToken = encryptionService.decrypt(cfg.getRefreshTokenEncrypted());
        } catch (Exception e) {
            log.error("[memsys-oauth] failed to decrypt refresh token for userId={}: {}",
                    cfg.getUserId(), e.getMessage());
            return Optional.empty();
        }
        if (cfg.getClientId() == null || cfg.getClientId().isBlank()) {
            log.error("[memsys-oauth] userId={} missing clientId; DCR registration incomplete", cfg.getUserId());
            return Optional.empty();
        }
        String clientSecret;
        try {
            clientSecret = encryptionService.decrypt(cfg.getClientSecretEncrypted());
        } catch (Exception e) {
            log.error("[memsys-oauth] failed to decrypt client_secret for userId={}: {}",
                    cfg.getUserId(), e.getMessage());
            return Optional.empty();
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("client_id", cfg.getClientId());
        form.put("client_secret", clientSecret);

        try {
            TokenSet tokens = postToken(form);
            cfg.setAccessTokenEncrypted(encryptionService.encrypt(tokens.accessToken()));
            // Cognito rotates RTs by default — when present in the response, replace ours.
            if (tokens.refreshToken() != null && !tokens.refreshToken().isBlank()) {
                cfg.setRefreshTokenEncrypted(encryptionService.encrypt(tokens.refreshToken()));
            }
            cfg.setExpiresAt(tokens.expiresAt());
            if (tokens.scope() != null && !tokens.scope().isBlank()) cfg.setScopes(tokens.scope());
            repository.save(cfg);
            return Optional.of(tokens.accessToken());
        } catch (Exception e) {
            log.error("[memsys-oauth] refresh failed for userId={}: {}", cfg.getUserId(), e.getMessage());
            return Optional.empty();
        }
    }

    // ── shared token-endpoint poster ───────────────────────────────────

    private TokenSet postToken(Map<String, String> form) {
        String formBody = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "=" + URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));

        HttpRequest req = HttpRequest.newBuilder(URI.create(props.getTokenUrl()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        HttpResponse<String> res = send(req);
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new MemsysOAuthException(
                    "token endpoint returned HTTP " + res.statusCode() + ": " + truncate(res.body(), 400));
        }

        try {
            JsonNode root = mapper.readTree(res.body());
            String access = root.path("access_token").asText(null);
            String refresh = root.path("refresh_token").asText(null);
            String idToken = root.path("id_token").asText(null);
            long expiresIn = root.path("expires_in").asLong(3600);
            String scope = root.path("scope").asText("");
            if (access == null) {
                throw new MemsysOAuthException("token response missing access_token: " + truncate(res.body(), 400));
            }
            ParsedIdToken id = parseIdToken(idToken);
            return new TokenSet(
                    access, refresh, idToken,
                    Instant.now().plusSeconds(expiresIn),
                    scope, id.sub(), id.displayName());
        } catch (Exception e) {
            if (e instanceof MemsysOAuthException me) throw me;
            throw new MemsysOAuthException("token response parse failed: " + truncate(res.body(), 400), e);
        }
    }

    private ParsedIdToken parseIdToken(String idToken) {
        if (idToken == null || idToken.isBlank()) return new ParsedIdToken(null, null);
        try {
            String[] parts = idToken.split("\\.");
            if (parts.length < 2) return new ParsedIdToken(null, null);
            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            JsonNode payload = mapper.readTree(payloadJson);
            String sub = payload.path("sub").asText(null);
            String email = payload.path("email").asText(null);
            String name = payload.path("name").asText(null);
            String display = name != null && !name.isBlank() ? name : email;
            return new ParsedIdToken(sub, display);
        } catch (Exception e) {
            log.warn("[memsys-oauth] failed to parse id_token: {}", e.getMessage());
            return new ParsedIdToken(null, null);
        }
    }

    private HttpResponse<String> send(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new MemsysOAuthException("HTTP transport failure: " + e.getMessage(), e);
        }
    }


    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    public record TokenSet(
            String accessToken,
            String refreshToken,
            String idToken,
            Instant expiresAt,
            String scope,
            String subject,
            String displayName
    ) {}

    private record ParsedIdToken(String sub, String displayName) {}
}
