package com.dtech.kitecon.market.facade;

import com.zerodhatech.models.*;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Broker-agnostic interface for market operations.
 * Abstracts underlying broker SDK (Zerodha, Dhan, Upstox, etc.)
 *
 * This interface uses Zerodha model classes for now (Quote, HistoricalData, etc.)
 * to minimize changes. Future versions can introduce broker-agnostic models.
 */
public interface MarketFacade {

    /**
     * Get the broker name (e.g., "zerodha", "dhan", "upstox")
     */
    String getBrokerName();

    /**
     * Check if this facade is available/configured
     */
    boolean isAvailable();

    /**
     * Get access token (for session management)
     */
    String getAccessToken();

    // ==================== User Profile ====================

    /**
     * Get user profile information
     *
     * @return Profile object
     * @throws MarketException if profile fetch fails
     */
    Profile getProfile() throws MarketException;

    // ==================== Market Data ====================

    /**
     * Get historical OHLC data
     *
     * @param from Start date
     * @param to End date
     * @param token Instrument token/security ID
     * @param interval Time interval (e.g., "day", "5minute")
     * @param continuous Whether to fetch continuous data
     * @param oi Whether to include open interest
     * @return Historical data
     * @throws MarketException if data fetch fails
     */
    HistoricalData getHistoricalData(
        Date from,
        Date to,
        String token,
        String interval,
        boolean continuous,
        boolean oi
    ) throws MarketException;

    /**
     * Get market quotes for multiple instruments
     *
     * @param instruments Array of instrument identifiers (e.g., "NSE:INFY")
     * @return Map of instrument to Quote
     * @throws MarketException if quote fetch fails
     */
    Map<String, Quote> getQuote(String[] instruments) throws MarketException;

    /**
     * Get last traded price for multiple instruments
     *
     * @param instruments Array of instrument tokens
     * @return Map of instrument to LTPQuote
     * @throws MarketException if LTP fetch fails
     */
    Map<String, LTPQuote> getLTP(String[] instruments) throws MarketException;

    // ==================== Instruments ====================

    /**
     * Get all tradable instruments
     *
     * @return List of instruments
     * @throws MarketException if instrument fetch fails
     */
    List<Instrument> getInstruments() throws MarketException;

    // ==================== Orders ====================

    /**
     * Place an order
     *
     * @param params Order parameters
     * @param variety Order variety (e.g., "regular", "amo", "bo", "co")
     * @return Order object with order ID
     * @throws MarketException if order placement fails
     */
    Order placeOrder(OrderParams params, String variety) throws MarketException;

    /**
     * Get trades for a specific order
     *
     * @param orderId Order ID
     * @return List of trades
     * @throws MarketException if trade fetch fails
     */
    List<Trade> getOrderTrades(String orderId) throws MarketException;

    // ==================== Session Management ====================

    /**
     * Generate session using request token
     * Used for OAuth flow
     *
     * @param requestToken Request token from OAuth callback
     * @param apiSecret API secret
     * @return User object with tokens
     * @throws MarketException if session generation fails
     */
    User generateSession(String requestToken, String apiSecret) throws MarketException;

    /**
     * Get login URL for OAuth flow
     *
     * @return Login URL
     * @throws MarketException if URL generation fails
     */
    String getLoginURL() throws MarketException;

    /**
     * Set access token
     *
     * @param accessToken Access token
     */
    void setAccessToken(String accessToken);

    /**
     * Set public token
     *
     * @param publicToken Public token
     */
    void setPublicToken(String publicToken);

    /**
     * Set user ID
     *
     * @param userId User ID
     */
    void setUserId(String userId);
}
