package com.dtech.kitecon.market.facade;

import com.dtech.dhan.config.DhanConnectConfig;
import com.dtech.dhan.facade.DhanMarketFacade;
import com.dtech.kitecon.config.KiteConnectPool;
import com.zerodhatech.kiteconnect.KiteConnect;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Provider for MarketFacade instances.
 *
 * Zerodha facades are obtained via KiteConnectPool which round-robins across
 * all registered Kite apps, multiplying the effective API rate limit.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MarketFacadeProvider {

    private final KiteConnectPool kiteConnectPool;
    private final DhanConnectConfig dhanConnectConfig;

    /**
     * Get default market facade (Zerodha, round-robin across pool).
     */
    public MarketFacade getFacade() {
        return getFacade(null);
    }

    /**
     * Get market facade for specific broker.
     */
    public MarketFacade getFacade(String brokerName) {
        if (brokerName == null || "zerodha".equalsIgnoreCase(brokerName)) {
            return createZerodhaFacade();
        }
        if ("dhan".equalsIgnoreCase(brokerName)) {
            return createDhanFacade();
        }
        throw new IllegalArgumentException("Unsupported broker: " + brokerName);
    }

    /**
     * Get market facade for a specific strategy (defaults to Zerodha).
     */
    public MarketFacade getFacadeForStrategy(String strategyId) {
        log.debug("Getting facade for strategy: {} (using default broker)", strategyId);
        return getFacade();
    }

    public String[] getAvailableBrokers() {
        List<String> brokers = new ArrayList<>();
        brokers.add("zerodha");
        if (dhanConnectConfig != null && dhanConnectConfig.isConfigured()) {
            brokers.add("dhan");
        }
        return brokers.toArray(new String[0]);
    }

    public boolean isBrokerAvailable(String brokerName) {
        try {
            return getFacade(brokerName).isAvailable();
        } catch (Exception e) {
            log.warn("Broker {} is not available: {}", brokerName, e.getMessage());
            return false;
        }
    }

    // ─────────────────────── Facade creation ──────────────────────────────

    /**
     * Creates a Zerodha facade using the next client from the pool (round-robin).
     * Each call may return a facade backed by a different KiteConnect instance,
     * distributing load and multiplying effective rate limits across N apps.
     */
    private MarketFacade createZerodhaFacade() {
        KiteConnect kc = kiteConnectPool.getNextClientForHistorical();
        if (kc == null) {
            throw new IllegalStateException(
                    "No authenticated Kite client available. Please complete /kite-login first.");
        }
        return new ZerodhaMarketFacade(kc);
    }

    private MarketFacade createDhanFacade() {
        if (dhanConnectConfig == null || !dhanConnectConfig.isConfigured()) {
            throw new IllegalStateException(
                    "Dhan is not configured. Missing access token. Please update via /api/dhan/update-token");
        }
        return new DhanMarketFacade(
                dhanConnectConfig.getDhanApiClient(),
                dhanConnectConfig.getAccessToken(),
                dhanConnectConfig.getClientId());
    }
}
