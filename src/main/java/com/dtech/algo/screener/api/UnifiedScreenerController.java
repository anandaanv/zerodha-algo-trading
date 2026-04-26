package com.dtech.algo.screener.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Unified REST API for all screener types.
 * Delegates to the appropriate Screener implementation via ScreenerRegistry.
 *
 * New endpoints (additive — old screener endpoints remain until UI migrates):
 *   POST /api/screeners/{type}/run
 *   GET  /api/screeners
 */
@Slf4j
@RestController
@RequestMapping("/api/screeners")
@RequiredArgsConstructor
public class UnifiedScreenerController {

    private final ScreenerRegistry registry;

    /**
     * List all registered screener types.
     */
    @GetMapping
    public ResponseEntity<?> listScreeners() {
        var screeners = registry.getAll().stream()
                .map(s -> Map.of("name", s.getName(), "type", s.getType().name()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(screeners);
    }

    /**
     * Run a screener by type.
     *
     * Body: { symbols: ["RELIANCE", "TCS"], config: { screenerId: 1, timeframe: "OneHour", ... } }
     */
    @PostMapping("/{type}/run")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> runScreener(@PathVariable String type,
                                          @RequestBody Map<String, Object> body) {
        ScreenerType screenerType;
        try {
            screenerType = ScreenerType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Unknown screener type: " + type,
                    "available", registry.getRegisteredTypes()));
        }

        var screener = registry.get(screenerType);
        if (screener.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Screener type not registered: " + type));
        }

        List<String> symbols = (List<String>) body.getOrDefault("symbols", List.of());
        Map<String, Object> config = (Map<String, Object>) body.getOrDefault("config", Map.of());

        try {
            List<? extends ScreenerResult> results = screener.get().scan(symbols, config);
            return ResponseEntity.ok(Map.of(
                    "type", screenerType.name(),
                    "screener", screener.get().getName(),
                    "resultCount", results.size(),
                    "results", results
            ));
        } catch (Exception e) {
            log.error("[UnifiedScreener] {} failed: {}", screenerType, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", e.getMessage(),
                    "type", screenerType.name()));
        }
    }
}
