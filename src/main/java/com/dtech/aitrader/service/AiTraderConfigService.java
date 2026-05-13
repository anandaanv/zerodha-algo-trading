package com.dtech.aitrader.service;

import com.dtech.aitrader.repository.AiTraderConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiTraderConfigService {
    private final AiTraderConfigRepository configRepo;
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    public String getString(String key, String defaultValue) {
        return cache.computeIfAbsent(key, k ->
                configRepo.findByConfigKey(k)
                        .map(cfg -> cfg.getConfigValue())
                        .orElse(defaultValue)
        );
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
}
