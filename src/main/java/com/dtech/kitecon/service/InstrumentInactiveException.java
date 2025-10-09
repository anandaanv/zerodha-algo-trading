package com.dtech.kitecon.service;

/**
 * Thrown when the instrument/token is inactive (e.g., expired derivative).
 */
public class InstrumentInactiveException extends RuntimeException {
    public InstrumentInactiveException(String message) {
        super(message);
    }
}
