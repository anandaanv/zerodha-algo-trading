package com.dtech.algo.screener.api;

import java.time.Instant;
import java.util.Map;

/**
 * Common result contract for all screener types.
 */
public interface ScreenerResult {
    String getSymbol();
    Instant getTimestamp();
    double getConfidence();
    Map<String, Object> getMetadata();
}
