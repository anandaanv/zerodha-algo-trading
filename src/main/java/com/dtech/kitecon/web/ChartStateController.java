package com.dtech.kitecon.web;

import com.dtech.kitecon.data.UserChartState;
import com.dtech.kitecon.repository.UserChartStateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chart-state")
@CrossOrigin
public class ChartStateController {

    private final UserChartStateRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * Save drawings for a symbol + layoutId + optional timeframe combination
     * POST /api/chart-state/drawings
     * Body: { "symbol":"TCS", "layoutId": 1, "timeframe": "60", "drawings": {...} }
     * timeframe is optional - if not provided, falls back to legacy behavior (keyed by symbol+layoutId)
     */
    @PostMapping("/drawings")
    public ResponseEntity<?> saveDrawings(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            String symbol = (String) body.get("symbol");
            Object layoutIdObj = body.get("layoutId");
            Object drawingsObj = body.get("drawings");
            String timeframe = (String) body.get("timeframe");

            if (symbol == null || layoutIdObj == null) {
                return ResponseEntity.badRequest().build();
            }

            Long layoutId = layoutIdObj instanceof Number
                ? ((Number) layoutIdObj).longValue()
                : Long.parseLong(layoutIdObj.toString());

            String drawingsJson = drawingsObj == null ? "{}" : objectMapper.writeValueAsString(drawingsObj);

            // Try to find existing state by (symbol, layoutId, timeframe) if timeframe provided
            UserChartState state;
            if (timeframe != null && !timeframe.isEmpty()) {
                state = repository.findBySymbolAndLayoutIdAndTimeframe(symbol, layoutId, timeframe)
                        .orElse(UserChartState.builder()
                                .symbol(symbol)
                                .layoutId(layoutId)
                                .timeframe(timeframe)
                                .build());
            } else {
                // Legacy path: no timeframe specified, use old query
                state = repository.findBySymbolAndLayoutId(symbol, layoutId)
                        .orElse(UserChartState.builder()
                                .symbol(symbol)
                                .layoutId(layoutId)
                                .timeframe(null)
                                .build());
            }

            state.setOverlaysJson(drawingsJson);
            repository.save(state);

            return ResponseEntity.ok(Map.of("status", "saved"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Load drawings for a symbol + layoutId + optional timeframe combination
     * GET /api/chart-state/drawings?symbol=TCS&layoutId=1&timeframe=60
     * If timeframe is provided: try TF-specific row first, fall back to NULL-timeframe (legacy) row
     * If timeframe is not provided: use original findBySymbolAndLayoutId (legacy behavior)
     */
    @GetMapping("/drawings")
    public ResponseEntity<?> loadDrawings(
            @RequestParam String symbol,
            @RequestParam Long layoutId,
            @RequestParam(required = false) String timeframe,
            Authentication auth) {
        try {
            Optional<UserChartState> stateOpt;
            
            if (timeframe != null && !timeframe.isEmpty()) {
                // Try TF-specific row first
                stateOpt = repository.findBySymbolAndLayoutIdAndTimeframe(symbol, layoutId, timeframe);
                // Fall back to legacy NULL-timeframe row if not found
                if (stateOpt.isEmpty()) {
                    stateOpt = repository.findBySymbolAndLayoutIdAndTimeframeIsNull(symbol, layoutId);
                }
            } else {
                // Legacy path: no timeframe, use original query
                stateOpt = repository.findBySymbolAndLayoutId(symbol, layoutId);
            }

            if (stateOpt.isEmpty()) {
                return ResponseEntity.ok(Map.of());
            }

            Map<String, Object> drawings = objectMapper.readValue(stateOpt.get().getOverlaysJson(), Map.class);
            return ResponseEntity.ok(drawings);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Delete drawings for a symbol + layoutId + optional timeframe combination
     * DELETE /api/chart-state?symbol=TCS&layoutId=1&timeframe=60
     * If timeframe is provided: delete only the TF-specific row
     * If timeframe is not provided: use original delete (legacy behavior)
     */
    @DeleteMapping
    public ResponseEntity<?> deleteDrawings(
            @RequestParam String symbol,
            @RequestParam Long layoutId,
            @RequestParam(required = false) String timeframe,
            Authentication auth) {
        try {
            Optional<UserChartState> stateOpt;
            
            if (timeframe != null && !timeframe.isEmpty()) {
                // Delete TF-specific row
                stateOpt = repository.findBySymbolAndLayoutIdAndTimeframe(symbol, layoutId, timeframe);
            } else {
                // Legacy path: delete by (symbol, layoutId) only
                stateOpt = repository.findBySymbolAndLayoutId(symbol, layoutId);
            }
            
            stateOpt.ifPresent(repository::delete);
            return ResponseEntity.ok(Map.of("status", "deleted"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}
