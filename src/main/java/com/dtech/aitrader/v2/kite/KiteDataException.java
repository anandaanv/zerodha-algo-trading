package com.dtech.aitrader.v2.kite;

/** Wraps any KiteConnect SDK exception so callers don't depend on the SDK's checked types. */
public class KiteDataException extends RuntimeException {
    public KiteDataException(String message) { super(message); }
    public KiteDataException(String message, Throwable cause) { super(message, cause); }
}
