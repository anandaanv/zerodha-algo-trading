package com.dtech.algo.screener.api;

import com.dtech.algo.screener.ScreenerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * Adapts the existing Kotlin DSL ScreenerService to the unified Screener interface.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DslScreenerAdapter implements Screener<DslScreenerAdapter.DslResult> {

    private final ScreenerService screenerService;

    @Override
    public String getName() { return "DSL Screener"; }

    @Override
    public ScreenerType getType() { return ScreenerType.DSL; }

    @Override
    public List<DslResult> scan(List<String> symbols, Map<String, Object> config) {
        long screenerId = ((Number) config.getOrDefault("screenerId", 0)).longValue();
        String timeframe = (String) config.getOrDefault("timeframe", null);
        int nowIndex = ((Number) config.getOrDefault("nowIndex", -1)).intValue();

        List<DslResult> results = new ArrayList<>();
        for (String symbol : symbols) {
            try {
                screenerService.run(screenerId, symbol, nowIndex, timeframe, null, null);
                // DSL screener uses callbacks — results captured via SignalCallback
                // For unified interface, we wrap the execution status
                results.add(new DslResult(symbol, Instant.now(), 1.0, Map.of("screenerId", screenerId)));
            } catch (Exception e) {
                log.warn("[DslScreener] Failed for {}: {}", symbol, e.getMessage());
            }
        }
        return results;
    }

    public static class DslResult implements ScreenerResult {
        private final String symbol;
        private final Instant timestamp;
        private final double confidence;
        private final Map<String, Object> metadata;

        public DslResult(String symbol, Instant timestamp, double confidence, Map<String, Object> metadata) {
            this.symbol = symbol;
            this.timestamp = timestamp;
            this.confidence = confidence;
            this.metadata = metadata;
        }

        @Override public String getSymbol() { return symbol; }
        @Override public Instant getTimestamp() { return timestamp; }
        @Override public double getConfidence() { return confidence; }
        @Override public Map<String, Object> getMetadata() { return metadata; }
    }
}
