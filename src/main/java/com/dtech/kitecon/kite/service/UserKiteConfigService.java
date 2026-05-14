package com.dtech.kitecon.kite.service;

import com.dtech.kitecon.auth.UserRepository;
import com.dtech.kitecon.config.KiteConnectPool;
import com.dtech.kitecon.kite.entity.UserKiteConfig;
import com.dtech.kitecon.kite.repository.UserKiteConfigRepository;
import com.dtech.kitecon.service.copilot.CopilotEncryptionService;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserKiteConfigService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime KITE_TOKEN_EXPIRY = LocalTime.of(6, 0);

    private final UserKiteConfigRepository repository;
    private final UserRepository userRepository;
    private final KiteConnectPool kiteConnectPool;
    private final CopilotEncryptionService encryptionService;

    public List<Map<String, Object>> listAll() {
        return repository.findAll().stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public Map<String, Object> create(Long platformUserId, String label, String apiKey, String apiSecret, String kiteUserId) {
        UserKiteConfig config = UserKiteConfig.builder()
                .platformUserId(platformUserId)
                .label(label)
                .apiKey(apiKey)
                .apiSecret(apiSecret)
                .kiteUserId(kiteUserId)
                .active(true)
                .build();
        config = repository.save(config);
        return toDto(config);
    }

    @Transactional
    public Map<String, Object> update(Long id, String label, Boolean active,
                                       String apiKey, String apiSecret, String kiteUserId) {
        UserKiteConfig config = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Config not found: " + id));
        if (label != null) config.setLabel(label);
        if (active != null) config.setActive(active);
        // Optional credential fields — only update when explicitly provided.
        if (apiKey != null && !apiKey.isBlank()) config.setApiKey(apiKey);
        if (apiSecret != null && !apiSecret.isBlank()) config.setApiSecret(apiSecret);
        if (kiteUserId != null && !kiteUserId.isBlank()) config.setKiteUserId(kiteUserId);
        // Editing api_key or api_secret invalidates any cached pool client — drop it
        // so the next access reloads with the new values.
        if (apiKey != null || apiSecret != null) {
            kiteConnectPool.removeUserKiteConfig(id);
        }
        return toDto(repository.save(config));
    }

    @Transactional
    public void delete(Long id) {
        repository.deleteById(id);
        kiteConnectPool.removeUserKiteConfig(id);
    }

    public String getLoginUrl(Long id) {
        log.info("[KiteLogin] looking up UserKiteConfig id={}", id);
        UserKiteConfig config = repository.findById(id)
                .orElseThrow(() -> {
                    log.error("[KiteLogin] UserKiteConfig not found for id={}", id);
                    return new IllegalArgumentException("Config not found: " + id);
                });
        log.info("[KiteLogin] found config id={} label='{}' apiKey={} kiteUserId={}",
                id, config.getLabel(),
                config.getApiKey() != null ? config.getApiKey().substring(0, Math.min(4, config.getApiKey().length())) + "***" : "NULL",
                config.getKiteUserId());
        String url = "https://kite.trade/connect/login?api_key=" + config.getApiKey() + "&v=3";
        log.info("[KiteLogin] built login URL: {}", url);
        return url;
    }

    @Transactional
    public void processCallback(Long configId, String requestToken) throws Exception {
        UserKiteConfig config = repository.findById(configId)
                .orElseThrow(() -> new IllegalArgumentException("Config not found: " + configId));

        KiteConnect kc = new KiteConnect(config.getApiKey());
        if (config.getKiteUserId() != null) kc.setUserId(config.getKiteUserId());

        com.zerodhatech.models.User kiteUser = null;
        try {
            kiteUser = kc.generateSession(requestToken, config.getApiSecret());
        } catch (KiteException e) {
            throw new Exception(e);
        }
        kc.setAccessToken(kiteUser.accessToken);
        kc.setPublicToken(kiteUser.publicToken);

        config.setAccessToken(kiteUser.accessToken);
        config.setPublicToken(kiteUser.publicToken);
        repository.save(config);

        kiteConnectPool.reloadUserKiteConfig(configId, kc);
        log.info("UserKiteConfig {}: OAuth complete, tokens saved", configId);
    }

    /**
     * Stores Zerodha login credentials needed for headless auto-login.
     * Both values encrypted at rest. Plain values never echoed back.
     */
    @Transactional
    public void setAutoLoginCredentials(Long configId, String plainPassword, String plainTotpSecret) {
        UserKiteConfig config = repository.findById(configId)
                .orElseThrow(() -> new IllegalArgumentException("Config not found: " + configId));
        if (plainPassword != null && !plainPassword.isBlank()) {
            config.setZerodhaPasswordEncrypted(encryptionService.encrypt(plainPassword));
        }
        if (plainTotpSecret != null && !plainTotpSecret.isBlank()) {
            config.setTotpSecretEncrypted(encryptionService.encrypt(plainTotpSecret.trim()));
        }
        repository.save(config);
        log.info("UserKiteConfig {}: auto-login credentials saved", configId);
    }

    /**
     * Access tokens issued by Kite Connect expire ~06:00 IST every morning.
     * A token is "fresh" if its updated_at is after the most recent 06:00 IST boundary.
     */
    public boolean isAccessTokenFresh(UserKiteConfig cfg) {
        if (cfg.getAccessToken() == null || cfg.getAccessToken().isBlank()) return false;
        Instant updatedAt = cfg.getUpdatedAt();
        if (updatedAt == null) return false;
        ZonedDateTime now = ZonedDateTime.now(IST);
        ZonedDateTime todaySixAm = LocalDate.now(IST).atTime(KITE_TOKEN_EXPIRY).atZone(IST);
        ZonedDateTime cutoff = now.isBefore(todaySixAm) ? todaySixAm.minusDays(1) : todaySixAm;
        return !updatedAt.isBefore(cutoff.toInstant());
    }

    @Transactional
    public void disconnect(Long id) {
        UserKiteConfig config = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Config not found: " + id));
        config.setAccessToken(null);
        config.setPublicToken(null);
        repository.save(config);
        kiteConnectPool.removeUserKiteConfig(id);
    }

    private Map<String, Object> toDto(UserKiteConfig c) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", c.getId());
        dto.put("platformUserId", c.getPlatformUserId());
        userRepository.findById(c.getPlatformUserId()).ifPresent(u -> dto.put("platformUsername", u.getUsername()));
        dto.put("label", c.getLabel());
        dto.put("apiKey", maskApiKey(c.getApiKey()));
        dto.put("kiteUserId", c.getKiteUserId());
        dto.put("connected", c.getAccessToken() != null);
        dto.put("active", c.isActive());
        // Surface whether auto-login credentials are configured (boolean only, never the values).
        dto.put("hasAutoLoginPassword",
                c.getZerodhaPasswordEncrypted() != null && !c.getZerodhaPasswordEncrypted().isBlank());
        dto.put("hasAutoLoginTotp",
                c.getTotpSecretEncrypted() != null && !c.getTotpSecretEncrypted().isBlank());
        dto.put("createdAt", c.getCreatedAt());
        dto.put("updatedAt", c.getUpdatedAt());
        return dto;
    }

    private String maskApiKey(String key) {
        if (key == null || key.length() < 6) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 2);
    }
}
