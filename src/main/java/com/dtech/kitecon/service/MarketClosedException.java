package com.dtech.kitecon.service;

/**
 * Thrown by data download layer when the market is considered closed
 * according to configured trading window and overrides.
 * Handlers can catch this explicitly and reschedule accordingly.
 */
public class MarketClosedException extends RuntimeException {
    public MarketClosedException(String message) {
        super(message);
    }

    public MarketClosedException(String message, Throwable cause) {
        super(message, cause);
    }
}
