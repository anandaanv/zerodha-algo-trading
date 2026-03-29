package com.dtech.kitecon.config;

import com.dtech.kitecon.data.AppSecrets;
import com.dtech.kitecon.kite.entity.UserKiteConfig;
import com.dtech.kitecon.kite.repository.UserKiteConfigRepository;
import com.dtech.kitecon.persistence.KiteConnectSettings;
import com.dtech.kitecon.repository.AppSecretsRepository;
import com.dtech.kitecon.repository.KiteConnectSettingsRepository;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.User;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pool of KiteConnect instances — one per registered Kite developer app.
 *
 * Multiple apps multiply rate limits (3 req/sec per app for historical API)
 * and WebSocket capacity (3 connections × 3000 instruments per app).
 *
 * Auth flow: chained OAuth redirects through each app in sequence.
 * Kite browser session is shared across apps (same kite.zerodha.com domain),
 * so from Day 2 the user just clicks Login and all redirects complete instantly.
 *
 * Credentials loaded from:
 *   - kite_connect_settings DB table (id = appIndex + 1, for backward compat id=1 → app 0)
 *   - OR app_secrets table with keys: kite.api.key (app 0 legacy), kite.app.N.api.key (app N)
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class KiteConnectPool {

    @Value("${kite.apps.count:1}")
    private int appCount;

    @Value("${SECRETS_ENV:dev}")
    private String secretsEnv;

    private final KiteConnectSettingsRepository settingsRepository;
    private final AppSecretsRepository appSecretsRepository;
    private final UserKiteConfigRepository userKiteConfigRepository;

    private final List<KiteConnect> clients = new ArrayList<>();
    private final List<KiteAppCredentials> credentials = new ArrayList<>();
    private final AtomicInteger roundRobin = new AtomicInteger(0);
    private final Map<Long, KiteConnect> userConfigClients = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        credentials.clear();
        clients.clear();
        for (int i = 0; i < appCount; i++) {
            credentials.add(loadCredentials(i));
        }
        initAllFromDatabase();
        loadFromUserKiteConfigs();
    }

    // ─────────────────────────── Initialisation ───────────────────────────

    private KiteAppCredentials loadCredentials(int appIndex) {
        // DB row id: app 0 → id=1 (legacy), app N → id=N+1
        long dbId = (long) (appIndex + 1);
        KiteConnectSettings settings = settingsRepository.findById(dbId).orElse(null);

        String apiKey = null, secret = null, userId = null;

        if (settings != null && settings.getApiKey() != null) {
            apiKey  = settings.getApiKey();
            secret  = settings.getSecret();
            userId  = settings.getUserId();
        } else {
            // Fallback: app_secrets table
            if (appIndex == 0) {
                // Legacy key names for the primary app
                apiKey = getSecret("kite.api.key");
                secret = getSecret("kite.api.secret");
                userId = getSecret("kite.api.user");
            } else {
                apiKey = getSecret("kite.app." + appIndex + ".api.key");
                secret = getSecret("kite.app." + appIndex + ".secret");
                userId = getSecret("kite.app." + appIndex + ".user.id");
            }
        }

        log.info("Kite pool app {}: apiKey={}", appIndex,
                apiKey != null ? apiKey.substring(0, Math.min(4, apiKey.length())) + "***" : "null");
        return new KiteAppCredentials(appIndex, apiKey, secret, userId);
    }

    /**
     * (Re)builds the client list from persisted tokens.
     * Called on startup and after any token refresh.
     */
    public void initAllFromDatabase() {
        clients.clear();
        for (int i = 0; i < credentials.size(); i++) {
            KiteAppCredentials creds = credentials.get(i);
            if (creds.getApiKey() == null) {
                log.warn("Kite pool app {}: no API key, skipping", i);
                clients.add(null);
                continue;
            }

            KiteConnect kc = new KiteConnect(creds.getApiKey());
            if (creds.getUserId() != null) kc.setUserId(creds.getUserId());

            long dbId = (long) (i + 1);
            settingsRepository.findById(dbId).ifPresent(s -> {
                if (s.getAccessToken() != null)  kc.setAccessToken(s.getAccessToken());
                if (s.getPublicToken() != null)  kc.setPublicToken(s.getPublicToken());
            });

            clients.add(kc);
            log.info("Kite pool app {}: client ready (token={})", i, kc.getAccessToken() != null ? "present" : "missing");
        }
    }

    /**
     * Exchange a request_token for the given app index.
     * Called from {@link com.dtech.kitecon.controller.ConfigController} during OAuth callback.
     */
    public void initializeApp(int appIndex, String requestToken) throws KiteException, IOException {
        if (appIndex >= credentials.size()) {
            throw new IllegalArgumentException("App index " + appIndex + " >= pool size " + credentials.size());
        }
        KiteAppCredentials creds = credentials.get(appIndex);
        if (creds.getApiKey() == null) {
            throw new IllegalStateException("No API key configured for app " + appIndex);
        }

        KiteConnect kc = new KiteConnect(creds.getApiKey());
        if (creds.getUserId() != null) kc.setUserId(creds.getUserId());

        User user = kc.generateSession(requestToken, creds.getSecret());
        kc.setAccessToken(user.accessToken);
        kc.setPublicToken(user.publicToken);

        // Update pool slot
        while (clients.size() <= appIndex) clients.add(null);
        clients.set(appIndex, kc);

        // Persist
        long dbId = (long) (appIndex + 1);
        KiteConnectSettings settings = settingsRepository.findById(dbId).orElseGet(() -> {
            KiteConnectSettings s = new KiteConnectSettings();
            s.setId(dbId);
            return s;
        });
        settings.setApiKey(creds.getApiKey());
        settings.setUserId(creds.getUserId());
        settings.setSecret(creds.getSecret());
        settings.setAccessToken(user.accessToken);
        settings.setPublicToken(user.publicToken);
        settingsRepository.save(settings);

        log.info("Kite pool app {}: authenticated and tokens persisted", appIndex);
    }

    // ─────────────────────────── Client access ────────────────────────────

    /**
     * Round-robin client for historical/REST API calls.
     * Distributes load across all authenticated apps.
     */
    public KiteConnect getNextClientForHistorical() {
        List<KiteConnect> available = new ArrayList<>();
        // User kite configs take priority
        available.addAll(userConfigClients.values());
        // Then legacy app pool clients
        clients.stream().filter(c -> c != null && c.getAccessToken() != null).forEach(available::add);
        if (available.isEmpty()) return null;
        int idx = Math.abs(roundRobin.getAndIncrement()) % available.size();
        return available.get(idx);
    }

    /**
     * Primary client (app 0) — for WebSocket ticker init and backward compat.
     */
    public KiteConnect getPrimaryClient() {
        // Prefer user kite config clients
        if (!userConfigClients.isEmpty()) {
            return userConfigClients.values().iterator().next();
        }
        return clients.stream()
                .filter(c -> c != null && c.getAccessToken() != null)
                .findFirst()
                .orElse(clients.isEmpty() ? null : clients.get(0));
    }

    /**
     * All authenticated clients — one KiteTicker per client for maximum instrument capacity.
     */
    public List<KiteConnect> getAllAuthenticatedClients() {
        List<KiteConnect> result = new ArrayList<>();
        result.addAll(userConfigClients.values());
        clients.stream().filter(c -> c != null && c.getAccessToken() != null).forEach(result::add);
        return result;
    }

    public int getAppCount() {
        return appCount;
    }

    /**
     * Kite OAuth login URL for a given app index.
     * App N's redirect URI in Kite developer console must be: /app/{N}
     */
    public String getLoginUrl(int appIndex) {
        if (appIndex >= credentials.size()) return null;
        String key = credentials.get(appIndex).getApiKey();
        if (key == null) return null;
        return "https://kite.trade/connect/login?api_key=" + key + "&v=3";
    }

    /**
     * Loads all active UserKiteConfig records that have access tokens into the pool.
     * Called on startup and can be called to refresh.
     */
    public void loadFromUserKiteConfigs() {
        userConfigClients.clear();
        List<UserKiteConfig> activeConfigs = userKiteConfigRepository.findByActiveTrue();
        for (UserKiteConfig config : activeConfigs) {
            if (config.getApiKey() == null || config.getAccessToken() == null) continue;
            KiteConnect kc = new KiteConnect(config.getApiKey());
            if (config.getKiteUserId() != null) kc.setUserId(config.getKiteUserId());
            kc.setAccessToken(config.getAccessToken());
            if (config.getPublicToken() != null) kc.setPublicToken(config.getPublicToken());
            userConfigClients.put(config.getId(), kc);
            log.info("Loaded UserKiteConfig {}: apiKey={}...", config.getId(),
                    config.getApiKey().substring(0, Math.min(4, config.getApiKey().length())));
        }
        log.info("Loaded {} user kite config client(s)", userConfigClients.size());
    }

    /**
     * Add or replace a single user kite config client (called after OAuth).
     */
    public void reloadUserKiteConfig(Long configId, KiteConnect kc) {
        userConfigClients.put(configId, kc);
        log.info("UserKiteConfig {} client updated in pool", configId);
    }

    /**
     * Remove a user kite config client from the pool (called on delete/disconnect).
     */
    public void removeUserKiteConfig(Long configId) {
        userConfigClients.remove(configId);
    }

    // ─────────────────────────── Helpers ──────────────────────────────────

    private String getSecret(String key) {
        return appSecretsRepository.findByEnvAndPropKey(secretsEnv, key)
                .map(AppSecrets::getPropValue)
                .orElse(null);
    }

    @Data
    @AllArgsConstructor
    private static class KiteAppCredentials {
        private final int appIndex;
        private final String apiKey;
        private final String secret;
        private final String userId;
    }
}
