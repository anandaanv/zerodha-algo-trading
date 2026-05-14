package com.dtech.kitecon.kite.service;

import com.dtech.kitecon.kite.entity.UserKiteConfig;
import com.dtech.kitecon.service.copilot.CopilotEncryptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.User;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Headless Zerodha Kite Connect auto-login.
 *
 * Replicates the browser login flow over plain HTTPS:
 *   1. POST /api/login         (user_id + password)               → request_id
 *   2. Generate TOTP from base32 shared secret                    → 6-digit code
 *   3. POST /api/twofa         (user_id + request_id + code)      → 2FA cleared, session cookie set
 *   4. GET  /connect/login     (api_key, follow redirects)        → request_token in final URL
 *   5. KiteConnect.generateSession(requestToken, apiSecret)       → access_token + public_token
 *
 * The Zerodha private login API (/api/login, /api/twofa) is what the kite.zerodha.com
 * web UI uses. Officially tolerated for trader auto-login; Kite Connect exists for this purpose.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KiteAutoLoginService {

    private static final String BASE = "https://kite.zerodha.com";
    private static final int MAX_REDIRECTS = 10;

    private final CopilotEncryptionService encryptionService;
    private final ObjectMapper objectMapper;
    private final TimeProvider timeProvider = new SystemTimeProvider();
    private final CodeGenerator codeGenerator = new DefaultCodeGenerator();

    public record LoginResult(String accessToken, String publicToken) {}

    public LoginResult login(UserKiteConfig cfg) {
        long start = System.currentTimeMillis();
        if (cfg.getZerodhaPasswordEncrypted() == null || cfg.getZerodhaPasswordEncrypted().isBlank()) {
            throw new IllegalStateException("Zerodha password not configured for config id=" + cfg.getId());
        }
        if (cfg.getTotpSecretEncrypted() == null || cfg.getTotpSecretEncrypted().isBlank()) {
            throw new IllegalStateException("TOTP secret not configured for config id=" + cfg.getId());
        }

        String password = encryptionService.decrypt(cfg.getZerodhaPasswordEncrypted());
        String totpSecret = encryptionService.decrypt(cfg.getTotpSecretEncrypted());

        // Shared cookie jar across all manual redirects
        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .cookieHandler(cookieManager)
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        try {
            // Step 1 — initial login
            String requestId = step1Login(client, cfg.getKiteUserId(), password);
            log.info("[auto-login {}] step1 ok, request_id len={}", cfg.getId(), requestId.length());

            // Step 2 — TOTP
            String totpCode = generateTotp(totpSecret);
            log.info("[auto-login {}] step2 ok, TOTP generated", cfg.getId());

            // Step 3 — submit 2FA
            step3Twofa(client, cfg.getKiteUserId(), requestId, totpCode);
            log.info("[auto-login {}] step3 ok, 2FA accepted", cfg.getId());

            // Step 4 — get request_token via connect/login redirect
            String requestToken = step4ConnectLogin(client, cfg.getApiKey());
            log.info("[auto-login {}] step4 ok, got request_token", cfg.getId());

            // Step 5 — exchange via SDK
            KiteConnect kc = new KiteConnect(cfg.getApiKey());
            if (cfg.getKiteUserId() != null) kc.setUserId(cfg.getKiteUserId());
            User user;
            try {
                user = kc.generateSession(requestToken, cfg.getApiSecret());
            } catch (KiteException ke) {
                throw new RuntimeException("step5 generateSession failed: " + ke.getMessage(), ke);
            }
            long duration = System.currentTimeMillis() - start;
            log.info("[auto-login {}] step5 ok, access_token issued (total {}ms)", cfg.getId(), duration);

            return new LoginResult(user.accessToken, user.publicToken);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[auto-login {}] failed after {}ms: {}", cfg.getId(), duration, e.getMessage());
            throw new RuntimeException("Kite auto-login failed: " + e.getMessage(), e);
        }
    }

    private String step1Login(HttpClient client, String userId, String password) throws Exception {
        String body = "user_id=" + enc(userId) + "&password=" + enc(password);
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/api/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "Mozilla/5.0 (compatible; AlgoTrader/1.0)")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(20))
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new RuntimeException("step1 /api/login HTTP " + res.statusCode() + ": " + truncate(res.body(), 300));
        }
        JsonNode root = objectMapper.readTree(res.body());
        JsonNode data = root.path("data");
        String requestId = data.path("request_id").asText(null);
        if (requestId == null || requestId.isBlank()) {
            throw new RuntimeException("step1 /api/login response missing data.request_id: " + truncate(res.body(), 300));
        }
        return requestId;
    }

    private void step3Twofa(HttpClient client, String userId, String requestId, String totpCode) throws Exception {
        String body = "user_id=" + enc(userId)
                + "&request_id=" + enc(requestId)
                + "&twofa_value=" + enc(totpCode)
                + "&twofa_type=totp";
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/api/twofa"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "Mozilla/5.0 (compatible; AlgoTrader/1.0)")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(20))
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new RuntimeException("step3 /api/twofa HTTP " + res.statusCode() + ": " + truncate(res.body(), 300));
        }
    }

    private String step4ConnectLogin(HttpClient client, String apiKey) throws Exception {
        String url = BASE + "/connect/login?v=3&api_key=" + enc(apiKey);
        URI current = URI.create(url);
        for (int i = 0; i < MAX_REDIRECTS; i++) {
            HttpRequest req = HttpRequest.newBuilder(current)
                    .header("User-Agent", "Mozilla/5.0 (compatible; AlgoTrader/1.0)")
                    .GET()
                    .timeout(Duration.ofSeconds(20))
                    .build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());

            String token = extractRequestToken(current);
            if (token != null) return token;

            int code = res.statusCode();
            if (code >= 300 && code < 400) {
                String loc = res.headers().firstValue("Location").orElse(null);
                if (loc == null) {
                    throw new RuntimeException("step4 redirect missing Location header at " + current);
                }
                URI next = current.resolve(loc);
                String tokenInRedirect = extractRequestToken(next);
                if (tokenInRedirect != null) return tokenInRedirect;
                current = next;
                continue;
            }
            if (code == 200) {
                // Final non-redirect — maybe the request_token came back via a meta refresh, etc.
                // Best-effort scan the body for request_token=
                String body = res.body() != null ? res.body() : "";
                int idx = body.indexOf("request_token=");
                if (idx >= 0) {
                    int end = body.indexOf("&", idx);
                    if (end < 0) end = body.indexOf("\"", idx);
                    if (end < 0) end = Math.min(body.length(), idx + 80);
                    String slice = body.substring(idx + "request_token=".length(), end);
                    return slice;
                }
                throw new RuntimeException("step4 ended at 200 without request_token. URL=" + current + " body=" + truncate(body, 200));
            }
            throw new RuntimeException("step4 unexpected HTTP " + code + " at " + current + " body=" + truncate(res.body(), 200));
        }
        throw new RuntimeException("step4 exceeded " + MAX_REDIRECTS + " redirects, last=" + current);
    }

    private String extractRequestToken(URI uri) {
        String query = uri.getQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String k = pair.substring(0, eq);
            if ("request_token".equals(k)) {
                String v = pair.substring(eq + 1);
                if (!v.isBlank()) return v;
            }
        }
        return null;
    }

    private String generateTotp(String base32Secret) throws Exception {
        long bucket = Math.floorDiv(timeProvider.getTime(), 30);
        return codeGenerator.generate(base32Secret, bucket);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
