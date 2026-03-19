package com.dtech.dhan.config;

import com.dtech.dhan.client.DhanApiClient;
import com.dtech.dhan.persistence.DhanConnectSettings;
import com.dtech.dhan.repository.DhanConnectSettingsRepository;
import com.dtech.kitecon.data.AppSecrets;
import com.dtech.kitecon.repository.AppSecretsRepository;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Dhan API Configuration
 * Manages Dhan authentication and access token persistence
 * Follows singleton pattern similar to KiteConnectConfig
 */
@Component
@Getter
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "dhan.enabled", havingValue = "true", matchIfMissing = false)
public class DhanConnectConfig {

    @Value("${dhan.client.id:}")
    private String clientId;

    @Value("${dhan.access.token:}")
    private String accessToken;

    @Value("${dhan.user.id:}")
    private String userId;

    @Value("${SECRETS_ENV:dev}")
    private String secretsEnv;

    private final DhanConnectSettingsRepository settingsRepository;
    private final AppSecretsRepository appSecretsRepository;
    private final DhanApiClient dhanApiClient;

    /**
     * Initialize Dhan configuration from database on startup
     */
    @PostConstruct
    public void initFromDatabase() {
        log.info("Initializing Dhan API configuration...");

        // Load from app_secrets if not set in application.properties
        if (clientId == null || clientId.trim().isEmpty()) {
            loadFromAppSecrets();
        }

        // Load or create settings from database
        Optional<DhanConnectSettings> existing = settingsRepository.findById(1L);
        DhanConnectSettings settings;

        if (existing.isPresent()) {
            settings = existing.get();
            log.info("Loaded Dhan settings from database for client: {}", settings.getClientId());

            // Override with database values if present
            if (settings.getClientId() != null) this.clientId = settings.getClientId();
            if (settings.getUserId() != null) this.userId = settings.getUserId();
            if (settings.getAccessToken() != null) this.accessToken = settings.getAccessToken();
        } else {
            // Create initial settings row
            log.info("Creating initial Dhan settings in database");
            settings = new DhanConnectSettings();
            settings.setId(1L);
            settings.setClientId(this.clientId);
            settings.setUserId(this.userId);
            settings.setAccessToken(this.accessToken);
            settingsRepository.save(settings);
        }

        if (accessToken != null && !accessToken.trim().isEmpty()) {
            log.info("Dhan API initialized with access token");
        } else {
            log.warn("Dhan API configured but no access token found. Please update via /api/dhan/update-token");
        }
    }

    /**
     * Update access token in database
     * Dhan tokens don't expire daily like Kite, but can be refreshed manually
     *
     * @param newAccessToken New access token
     * @param newUserId User ID associated with token
     */
    public void updateAccessToken(String newAccessToken, String newUserId) {
        log.info("Updating Dhan access token for user: {}", newUserId);

        DhanConnectSettings settings = settingsRepository.findById(1L)
                .orElseGet(() -> {
                    DhanConnectSettings s = new DhanConnectSettings();
                    s.setId(1L);
                    s.setClientId(this.clientId);
                    return s;
                });

        settings.setAccessToken(newAccessToken);
        settings.setUserId(newUserId);
        settingsRepository.save(settings);

        // Update in-memory values
        this.accessToken = newAccessToken;
        this.userId = newUserId;

        log.info("Dhan access token updated successfully");
    }

    /**
     * Load credentials from app_secrets table
     */
    private void loadFromAppSecrets() {
        log.info("Loading Dhan credentials from app_secrets table");

        Optional<AppSecrets> clientIdOpt = appSecretsRepository.findByEnvAndPropKey(secretsEnv, "dhan.client.id");
        Optional<AppSecrets> accessTokenOpt = appSecretsRepository.findByEnvAndPropKey(secretsEnv, "dhan.access.token");
        Optional<AppSecrets> userIdOpt = appSecretsRepository.findByEnvAndPropKey(secretsEnv, "dhan.user.id");

        clientIdOpt.ifPresent(s -> {
            this.clientId = s.getPropValue();
            log.debug("Loaded Dhan client ID from app_secrets");
        });
        accessTokenOpt.ifPresent(s -> {
            this.accessToken = s.getPropValue();
            log.debug("Loaded Dhan access token from app_secrets");
        });
        userIdOpt.ifPresent(s -> {
            this.userId = s.getPropValue();
            log.debug("Loaded Dhan user ID from app_secrets");
        });
    }

    /**
     * Check if Dhan API is properly configured and has valid token
     */
    public boolean isConfigured() {
        return clientId != null && !clientId.trim().isEmpty()
                && accessToken != null && !accessToken.trim().isEmpty();
    }

    /**
     * Get current access token
     */
    public String getAccessToken() {
        return accessToken;
    }

    /**
     * Bean for DhanApiClient (already created via @Component, this is optional)
     */
    @Bean
    public DhanApiClient getDhanApiClient() {
        return dhanApiClient;
    }
}
