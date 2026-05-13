package com.dtech.aitrader.service;

import com.dtech.aitrader.repository.AiTraderConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiTraderConfigService {
    private final AiTraderConfigRepository configRepo;

    // Cache entry: value + timestamp of when it was cached
    private static class CacheEntry {
        final String value;
        final long cachedAtMillis;

        CacheEntry(String value) {
            this.value = value;
            this.cachedAtMillis = System.currentTimeMillis();
        }

        boolean isExpired(long ttlMillis) {
            return System.currentTimeMillis() - cachedAtMillis > ttlMillis;
        }
    }

    private static final long CACHE_TTL_MILLIS = 30_000L; // 30 seconds
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public String getString(String key, String defaultValue) {
        CacheEntry entry = cache.get(key);

        // Check if entry exists and is not expired
        if (entry != null && !entry.isExpired(CACHE_TTL_MILLIS)) {
            return entry.value;
        }

        // Fetch from DB and cache
        String value = configRepo.findByConfigKey(key)
                .map(cfg -> cfg.getConfigValue())
                .orElse(defaultValue);

        cache.put(key, new CacheEntry(value));
        return value;
    }

    public Integer getInt(String key, Integer defaultValue) {
        String value = getString(key, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("Failed to parse int config {}: {}", key, value, e);
            return defaultValue;
        }
    }

    public Double getDouble(String key, Double defaultValue) {
        String value = getString(key, String.valueOf(defaultValue));
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            log.warn("Failed to parse double config {}: {}", key, value, e);
            return defaultValue;
        }
    }

    public Boolean getBoolean(String key, Boolean defaultValue) {
        String value = getString(key, String.valueOf(defaultValue));
        return Boolean.parseBoolean(value);
    }

    public void set(String key, String value) {
        var existing = configRepo.findByConfigKey(key);
        if (existing.isPresent()) {
            var config = existing.get();
            config.setConfigValue(value);
            configRepo.save(config);
        } else {
            configRepo.save(com.dtech.aitrader.data.AiTraderConfig.builder()
                    .configKey(key)
                    .configValue(value)
                    .updatedAt(java.time.LocalDateTime.now())
                    .build());
        }
        cache.remove(key);
        log.debug("Updated config: {} = {}", key, value);
    }

    public void refreshAll() {
        cache.clear();
        log.info("Cleared all cached configs");
    }
}
