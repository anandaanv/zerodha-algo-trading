package com.dtech.kitecon.config;

import com.dtech.kitecon.kite.entity.UserKiteConfig;
import com.dtech.kitecon.kite.repository.UserKiteConfigRepository;
import com.zerodhatech.kiteconnect.KiteConnect;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pool of KiteConnect instances — one per active UserKiteConfig.
 *
 * Uses DB-driven OAuth configs from the UserKiteConfig table (new flow).
 * Legacy multi-app OAuth chains (kite_connect_settings) are no longer supported.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class KiteConnectPool {

    private final UserKiteConfigRepository userKiteConfigRepository;

    private final AtomicInteger roundRobin = new AtomicInteger(0);
    private final Map<Long, KiteConnect> userConfigClients = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        loadFromUserKiteConfigs();
    }

    // ─────────────────────────── Client access ────────────────────────────

    /**
     * Round-robin client for historical/REST API calls.
     * Distributes load across all authenticated user configs.
     */
    public KiteConnect getNextClientForHistorical() {
        List<KiteConnect> available = new ArrayList<>(userConfigClients.values());
        if (available.isEmpty()) return null;
        int idx = Math.abs(roundRobin.getAndIncrement()) % available.size();
        return available.get(idx);
    }

    /**
     * Primary client — for WebSocket ticker init and backward compat.
     */
    public KiteConnect getPrimaryClient() {
        if (userConfigClients.isEmpty()) return null;
        return userConfigClients.values().iterator().next();
    }

    /**
     * All authenticated clients — one KiteTicker per client for maximum instrument capacity.
     */
    public List<KiteConnect> getAllAuthenticatedClients() {
        return new ArrayList<>(userConfigClients.values());
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

}
