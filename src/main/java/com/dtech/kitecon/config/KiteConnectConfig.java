package com.dtech.kitecon.config;

import com.zerodhatech.kiteconnect.KiteConnect;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Backward-compatible wrapper around KiteConnectPool.
 * Legacy code that injects KiteConnectConfig continues to work unchanged.
 * All new code should inject KiteConnectPool directly.
 */
@Component
@RequiredArgsConstructor
public class KiteConnectConfig {

    private final KiteConnectPool pool;

    /**
     * Returns the primary KiteConnect client.
     * Legacy consumers: KiteTickerService, ZerodhaBarSeriesLoader, etc.
     */
    public KiteConnect getKiteConnect() {
        return pool.getPrimaryClient();
    }
}
