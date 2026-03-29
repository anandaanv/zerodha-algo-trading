package com.dtech.kitecon.kite.service;

import com.dtech.kitecon.auth.UserRepository;
import com.dtech.kitecon.config.KiteConnectPool;
import com.dtech.kitecon.kite.entity.UserKiteConfig;
import com.dtech.kitecon.kite.repository.UserKiteConfigRepository;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserKiteConfigService {

    private final UserKiteConfigRepository repository;
    private final UserRepository userRepository;
    private final KiteConnectPool kiteConnectPool;

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
    public Map<String, Object> update(Long id, String label, Boolean active) {
        UserKiteConfig config = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Config not found: " + id));
        if (label != null) config.setLabel(label);
        if (active != null) config.setActive(active);
        return toDto(repository.save(config));
    }

    @Transactional
    public void delete(Long id) {
        repository.deleteById(id);
        kiteConnectPool.removeUserKiteConfig(id);
    }

    public String getLoginUrl(Long id) {
        UserKiteConfig config = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Config not found: " + id));
        return "https://kite.trade/connect/login?api_key=" + config.getApiKey() + "&v=3";
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
        dto.put("createdAt", c.getCreatedAt());
        dto.put("updatedAt", c.getUpdatedAt());
        return dto;
    }

    private String maskApiKey(String key) {
        if (key == null || key.length() < 6) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 2);
    }
}
