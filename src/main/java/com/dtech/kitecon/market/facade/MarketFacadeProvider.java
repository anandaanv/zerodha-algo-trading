package com.dtech.kitecon.market.facade;

import com.dtech.kitecon.config.KiteConnectConfig;
import com.zerodhatech.kiteconnect.KiteConnect;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Provider for MarketFacade instances.
 * Manages creation and selection of broker-specific facades.
 *
 * This component:
 * - Decides which broker facade to use (Zerodha, Dhan, Upstox, etc.)
 * - Can implement pooling for rate limit management (future)
 * - Can route strategies to specific brokers (future)
 *
 * NOT using @Bean because facades are not managed by Spring
 * - Allows multiple instances for pooling
 * - Allows per-strategy broker selection
 * - Cleaner lifecycle management
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MarketFacadeProvider {

    private final KiteConnectConfig kiteConnectConfig;
    // Future: Add other broker configs here
    // private final DhanConnectConfig dhanConnectConfig;
    // private final UpstoxConnectConfig upstoxConnectConfig;

    /**
     * Get default market facade (currently Zerodha)
     *
     * @return MarketFacade instance
     */
    public MarketFacade getFacade() {
        return getFacade(null);
    }

    /**
     * Get market facade for specific broker
     *
     * @param brokerName Broker name ("zerodha", "dhan", "upstox", null for default)
     * @return MarketFacade instance
     * @throws IllegalArgumentException if broker is not supported
     */
    public MarketFacade getFacade(String brokerName) {
        if (brokerName == null || "zerodha".equalsIgnoreCase(brokerName)) {
            return createZerodhaFacade();
        }

        // Future broker implementations:
        // if ("dhan".equalsIgnoreCase(brokerName)) {
        //     return createDhanFacade();
        // }
        // if ("upstox".equalsIgnoreCase(brokerName)) {
        //     return createUpstoxFacade();
        // }

        throw new IllegalArgumentException("Unsupported broker: " + brokerName);
    }

    /**
     * Get market facade for a specific strategy
     * Allows strategy-based broker routing
     *
     * @param strategyId Strategy identifier
     * @return MarketFacade instance
     */
    public MarketFacade getFacadeForStrategy(String strategyId) {
        // Future: Load broker preference from strategy configuration
        // Example:
        // String brokerPref = strategyConfigService.getBrokerPreference(strategyId);
        // return getFacade(brokerPref);

        // For now, return default (Zerodha)
        log.debug("Getting facade for strategy: {} (using default broker)", strategyId);
        return getFacade();
    }

    /**
     * Get available brokers
     *
     * @return Array of available broker names
     */
    public String[] getAvailableBrokers() {
        // Future: Dynamically detect which brokers are configured
        return new String[]{"zerodha"};
    }

    /**
     * Check if a specific broker is available
     *
     * @param brokerName Broker name
     * @return true if broker is configured and available
     */
    public boolean isBrokerAvailable(String brokerName) {
        try {
            MarketFacade facade = getFacade(brokerName);
            return facade.isAvailable();
        } catch (Exception e) {
            log.warn("Broker {} is not available: {}", brokerName, e.getMessage());
            return false;
        }
    }

    // ==================== Facade Creation Methods ====================

    /**
     * Create Zerodha market facade
     */
    private MarketFacade createZerodhaFacade() {
        KiteConnect kiteConnect = kiteConnectConfig.getKiteConnect();
        if (kiteConnect == null) {
            throw new IllegalStateException("KiteConnect not initialized. Call kiteConnectConfig.initFromDatabase() first.");
        }
        return new ZerodhaMarketFacade(kiteConnect);
    }

    /**
     * Create Dhan market facade (future)
     */
    // private MarketFacade createDhanFacade() {
    //     // Implementation when Dhan support is added
    //     throw new UnsupportedOperationException("Dhan support coming soon");
    // }

    /**
     * Create Upstox market facade (future)
     */
    // private MarketFacade createUpstoxFacade() {
    //     // Implementation when Upstox support is added
    //     throw new UnsupportedOperationException("Upstox support coming soon");
    // }
}
