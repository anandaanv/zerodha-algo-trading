package com.dtech.kitecon.kite.service;

import com.dtech.kitecon.config.KiteConnectPool;
import com.dtech.kitecon.kite.entity.UserKiteConfig;
import com.dtech.kitecon.kite.repository.UserKiteConfigRepository;
import com.zerodhatech.kiteconnect.KiteConnect;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Runs once per application startup. For each active UserKiteConfig with stored
 * Zerodha credentials, checks whether the persisted Kite access_token is still
 * within today's validity window (Kite tokens expire ~06:00 IST). If stale,
 * triggers the headless auto-login flow and refreshes the row + the pool.
 *
 * Async so backend startup is not blocked by network round-trips to Kite.
 * Per-config errors are caught and logged; one failure doesn't stop the others.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KiteAutoLoginStartup {

    private final UserKiteConfigRepository configRepo;
    private final UserKiteConfigService configService;
    private final KiteAutoLoginService autoLoginService;
    private final KiteConnectPool pool;

    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void onStartup() {
        log.info("KiteAutoLoginStartup: scanning active user_kite_config rows");
        List<UserKiteConfig> active = configRepo.findByActiveTrue();
        for (UserKiteConfig cfg : active) {
            try {
                refreshIfNeeded(cfg);
            } catch (Exception e) {
                log.error("Kite config #{} auto-login failed: {}", cfg.getId(), e.getMessage(), e);
            }
        }
        log.info("KiteAutoLoginStartup: scan complete ({} configs reviewed)", active.size());
    }

    @Transactional
    protected void refreshIfNeeded(UserKiteConfig cfg) {
        String label = String.format("#%d (%s)", cfg.getId(), cfg.getKiteUserId());
        if (configService.isAccessTokenFresh(cfg)) {
            log.info("Kite config {}: access_token fresh, skipping auto-login", label);
            return;
        }
        if (cfg.getZerodhaPasswordEncrypted() == null || cfg.getZerodhaPasswordEncrypted().isBlank()
                || cfg.getTotpSecretEncrypted() == null || cfg.getTotpSecretEncrypted().isBlank()) {
            log.warn("Kite config {}: auto-login credentials not configured — manual /connect required", label);
            return;
        }
        log.info("Kite config {}: access_token stale, running auto-login", label);

        KiteAutoLoginService.LoginResult result = autoLoginService.login(cfg);
        cfg.setAccessToken(result.accessToken());
        cfg.setPublicToken(result.publicToken());
        cfg.setUpdatedAt(Instant.now());
        configRepo.save(cfg);

        // Wire the freshly-authenticated client into the pool
        KiteConnect kc = new KiteConnect(cfg.getApiKey());
        if (cfg.getKiteUserId() != null) kc.setUserId(cfg.getKiteUserId());
        kc.setAccessToken(result.accessToken());
        if (result.publicToken() != null) kc.setPublicToken(result.publicToken());
        pool.reloadUserKiteConfig(cfg.getId(), kc);

        log.info("Kite config {}: auto-login succeeded, token refreshed", label);
    }
}
