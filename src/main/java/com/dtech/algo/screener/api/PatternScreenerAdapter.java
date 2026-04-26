package com.dtech.algo.screener.api;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.patternscanner.PatternScanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * Adapts PatternScanService to the unified Screener interface.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PatternScreenerAdapter implements Screener<PatternScreenerAdapter.PatternResult> {

    private final PatternScanService patternScanService;

    @Override
    public String getName() { return "Pattern Screener"; }

    @Override
    public ScreenerType getType() { return ScreenerType.PATTERN; }

    @Override
    public List<PatternResult> scan(List<String> symbols, Map<String, Object> config) {
        String watchingTf = (String) config.getOrDefault("watchingTimeframe", "FifteenMinute");
        String confirmTf = (String) config.getOrDefault("confirmTimeframe", "OneHour");

        List<PatternResult> results = new ArrayList<>();
        for (String symbol : symbols) {
            try {
                var scanResult = patternScanService.scan(symbol,
                        Interval.valueOf(watchingTf), Interval.valueOf(confirmTf));
                if (scanResult != null && scanResult.getPatterns() != null && !scanResult.getPatterns().isEmpty()) {
                    results.add(new PatternResult(symbol, Instant.now(),
                            scanResult.getPatterns().size(),
                            Map.of("patternCount", scanResult.getPatterns().size(),
                                    "watchingTf", watchingTf, "confirmTf", confirmTf)));
                }
            } catch (Exception e) {
                log.warn("[PatternScreener] Failed for {}: {}", symbol, e.getMessage());
            }
        }
        return results;
    }

    public static class PatternResult implements ScreenerResult {
        private final String symbol;
        private final Instant timestamp;
        private final double confidence;
        private final Map<String, Object> metadata;

        public PatternResult(String symbol, Instant timestamp, double confidence, Map<String, Object> metadata) {
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
