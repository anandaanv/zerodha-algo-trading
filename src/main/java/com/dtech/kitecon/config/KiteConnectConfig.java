package com.dtech.kitecon.config;

import com.dtech.kitecon.repository.AppSecretsRepository;
import com.dtech.kitecon.repository.KiteConnectSettingsRepository;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Backward-compatible wrapper around KiteConnectPool.
 *
 * Legacy code that injects KiteConnectConfig continues to work unchanged.
 * All new code should inject KiteConnectPool directly.
 */
@Component
@Getter
@RequiredArgsConstructor
public class KiteConnectConfig {

    @Value("${kite.api.key:}")
    private String apiKey;

    @Value("${kite.api.user:}")
    private String userId;

    @Value("${kite.api.secret:}")
    private String secret;

    @Value("${SECRETS_ENV:dev}")
    private String secretsEnv;

    private final KiteConnectPool pool;
    private final KiteConnectSettingsRepository settingsRepository;
    private final AppSecretsRepository appSecretsRepository;

    /**
     * Authenticate app 0 with the given request token.
     * Legacy entry point called from ConfigController /app endpoint.
     */
    public void initialize(String requestToken) throws KiteException, IOException {
        pool.initializeApp(0, requestToken);
    }

    /**
     * Restore all tokens from the database.
     * Called on startup by KiteTickerService.
     */
    public void initFromDatabase() {
        pool.initAllFromDatabase();
    }

    /**
     * Returns the primary KiteConnect client (app 0).
     * Legacy consumers: KiteTickerService, ZerodhaBarSeriesLoader, etc.
     */
    public KiteConnect getKiteConnect() {
        return pool.getPrimaryClient();
    }
}
