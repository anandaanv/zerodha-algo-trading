package com.dtech.kitecon.web;

import com.dtech.kitecon.data.UserChartState;
import com.dtech.kitecon.repository.UserChartStateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chart-state")
@CrossOrigin
public class ChartStateController {

    private final UserChartStateRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * Save drawings for a symbol + layoutId combination
     * POST /api/chart-state/drawings
     * Body: { "symbol":"TCS", "layoutId": 1, "drawings": {...} }
     */
    @PostMapping("/drawings")
    public ResponseEntity<?> saveDrawings(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            String symbol = (String) body.get("symbol");
            Object layoutIdObj = body.get("layoutId");
            Object drawingsObj = body.get("drawings");

            if (symbol == null || layoutIdObj == null) {
                return ResponseEntity.badRequest().build();
            }

            Long layoutId = layoutIdObj instanceof Number
                ? ((Number) layoutIdObj).longValue()
                : Long.parseLong(layoutIdObj.toString());

            String drawingsJson = drawingsObj == null ? "{}" : objectMapper.writeValueAsString(drawingsObj);

            UserChartState state = repository.findBySymbolAndLayoutId(symbol, layoutId)
                    .orElse(UserChartState.builder()
                            .symbol(symbol)
                            .layoutId(layoutId)
                            .build());

            state.setOverlaysJson(drawingsJson);
            repository.save(state);

            return ResponseEntity.ok(Map.of("status", "saved"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Load drawings for a symbol + layoutId combination
     * GET /api/chart-state/drawings?symbol=TCS&layoutId=1
     */
    @GetMapping("/drawings")
    public ResponseEntity<?> loadDrawings(
            @RequestParam String symbol,
            @RequestParam Long layoutId,
            Authentication auth) {
        try {
            UserChartState state = repository.findBySymbolAndLayoutId(symbol, layoutId).orElse(null);
            if (state == null) {
                return ResponseEntity.ok(Map.of());
            }

            Map<String, Object> drawings = objectMapper.readValue(state.getOverlaysJson(), Map.class);
            return ResponseEntity.ok(drawings);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Delete drawings for a symbol + layoutId combination
     * DELETE /api/chart-state?symbol=TCS&layoutId=1
     */
    @DeleteMapping
    public ResponseEntity<?> deleteDrawings(
            @RequestParam String symbol,
            @RequestParam Long layoutId,
            Authentication auth) {
        try {
            repository.findBySymbolAndLayoutId(symbol, layoutId)
                    .ifPresent(repository::delete);
            return ResponseEntity.ok(Map.of("status", "deleted"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}
