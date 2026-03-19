package com.dtech.kitecon.market.facade;

/**
 * Generic exception for market operations across all brokers.
 * Wraps broker-specific exceptions (KiteException, DhanException, etc.)
 */
public class MarketException extends Exception {

    private final String brokerName;
    private final String errorCode;

    public MarketException(String message) {
        super(message);
        this.brokerName = null;
        this.errorCode = null;
    }

    public MarketException(String message, Throwable cause) {
        super(message, cause);
        this.brokerName = null;
        this.errorCode = null;
    }

    public MarketException(String brokerName, String errorCode, String message, Throwable cause) {
        super(String.format("[%s] %s: %s", brokerName, errorCode, message), cause);
        this.brokerName = brokerName;
        this.errorCode = errorCode;
    }

    public String getBrokerName() {
        return brokerName;
    }

    public String getErrorCode() {
        return errorCode;
    }

    /**
     * Check if this is a rate limit error
     */
    public boolean isRateLimitError() {
        return errorCode != null && (
            errorCode.contains("429") ||
            errorCode.contains("rate_limit") ||
            errorCode.contains("too_many_requests")
        );
    }

    /**
     * Check if this is an authentication error
     */
    public boolean isAuthError() {
        return errorCode != null && (
            errorCode.contains("401") ||
            errorCode.contains("403") ||
            errorCode.contains("token") ||
            errorCode.contains("unauthorized")
        );
    }

    /**
     * Check if this is a network/timeout error
     */
    public boolean isNetworkError() {
        return getCause() instanceof java.io.IOException ||
               getCause() instanceof java.net.SocketTimeoutException;
    }
}
