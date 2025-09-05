package com.dtech.algo.service;

/**
 * Timeframe types for multi-timeframe analysis
 */
public enum TimeframeType {
    RIPPLE("ripple"),
    WAVE("wave"),
    TIDE("tide"),
    SUPER_TIDE("super_tide");

    private final String key;

    TimeframeType(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
