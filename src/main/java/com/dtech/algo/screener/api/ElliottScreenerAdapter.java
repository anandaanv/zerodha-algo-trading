package com.dtech.algo.screener.api;

import com.dtech.kitecon.screener.elliott.dto.ElliottScreenerRunResponse;
import com.dtech.kitecon.screener.elliott.service.ElliottScreenerService;
import com.dtech.kitecon.screener.elliott.entity.ElliottScreener;
import com.dtech.kitecon.screener.elliott.repository.ElliottScreenerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * Adapts ElliottScreenerService to the unified Screener interface.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ElliottScreenerAdapter implements Screener<ElliottScreenerAdapter.ElliottResult> {

    private final ElliottScreenerService elliottScreenerService;
    private final ElliottScreenerRepository elliottScreenerRepository;

    @Override
    public String getName() { return "Elliott Wave Screener"; }

    @Override
    public ScreenerType getType() { return ScreenerType.ELLIOTT; }

    @Override
    public List<ElliottResult> scan(List<String> symbols, Map<String, Object> config) {
        Long screenerId = ((Number) config.getOrDefault("screenerId", 0)).longValue();
        Long userId = ((Number) config.getOrDefault("userId", 0)).longValue();

        Optional<ElliottScreener> screener = elliottScreenerRepository.findById(screenerId);
        if (screener.isEmpty()) {
            log.warn("[ElliottScreener] Screener not found: {}", screenerId);
            return Collections.emptyList();
        }

        var response = elliottScreenerService.runScreener(screener.get(), userId);

        List<ElliottResult> results = new ArrayList<>();
        if (response != null && response.getId() != null) {
            results.add(new ElliottResult("ALL", Instant.now(), 1.0,
                    Map.of("runId", response.getId(), "screenerId", screenerId)));
        }
        return results;
    }

    public static class ElliottResult implements ScreenerResult {
        private final String symbol;
        private final Instant timestamp;
        private final double confidence;
        private final Map<String, Object> metadata;

        public ElliottResult(String symbol, Instant timestamp, double confidence, Map<String, Object> metadata) {
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
