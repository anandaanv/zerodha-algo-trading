package com.dtech.kitecon.web;

import com.dtech.kitecon.data.UserChartState;
import com.dtech.kitecon.repository.UserChartStateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
@CrossOrigin // adjust origins as needed
public class ChartStateController {

    private final UserChartStateRepository repository;
    private final ObjectMapper objectMapper;

    public static record ChartStateDTO(
            String symbol,
            String period,
            Map<String, Object> overlays,
            Map<String, Object> meta,
            LocalDateTime createdAt
    ) {}

    /**
     * Save chart state (overlays and optional metadata) for a symbol+period.
     * Body example: { "symbol":"TCS", "period":"1h", "overlays": { "trendline": [...], ... } }
     */
    @PostMapping("/chart-state")
    public ResponseEntity<ChartStateDTO> save(@RequestBody Map<String, Object> body) {
        try {
            String symbol = (String) body.get("symbol");
            String period = (String) body.get("period");
            Object overlaysObj = body.get("overlays");
            Object metaObj = body.get("meta");

            if (symbol == null || period == null) return ResponseEntity.badRequest().build();

            String overlaysJson = overlaysObj == null ? "{}" : objectMapper.writeValueAsString(overlaysObj);
            String metaJson = metaObj == null ? null : objectMapper.writeValueAsString(metaObj);

            UserChartState ent = UserChartState.builder()
                    .symbol(symbol)
                    .period(period)
                    .overlaysJson(overlaysJson)
                    .metaJson(metaJson)
                    .createdAt(LocalDateTime.now())
                    .build();

            ent = repository.save(ent);

            Map<String, Object> overlaysMap = overlaysObj instanceof Map ? (Map<String, Object>) overlaysObj : objectMapper.readValue(overlaysJson, Map.class);
            Map<String, Object> metaMap = metaObj instanceof Map ? (Map<String, Object>) metaObj : (metaJson == null ? Map.of() : objectMapper.readValue(metaJson, Map.class));

            ChartStateDTO dto = new ChartStateDTO(ent.getSymbol(), ent.getPeriod(), overlaysMap, metaMap, ent.getCreatedAt());
            return ResponseEntity.ok(dto);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Load latest chart state for given symbol+period.
     * GET /api/chart-state?symbol=TCS&period=1h
     */
    @GetMapping("/chart-state")
    public ResponseEntity<ChartStateDTO> load(@RequestParam @NotBlank String symbol, @RequestParam @NotBlank String period) {
        try {
            UserChartState ent = repository.findTopBySymbolAndPeriodOrderByCreatedAtDesc(symbol, period);
            if (ent == null) return ResponseEntity.notFound().build();

            Map<String, Object> overlays = objectMapper.readValue(ent.getOverlaysJson(), Map.class);
            Map<String, Object> meta = ent.getMetaJson() == null ? Map.of() : objectMapper.readValue(ent.getMetaJson(), Map.class);
            ChartStateDTO dto = new ChartStateDTO(ent.getSymbol(), ent.getPeriod(), overlays, meta, ent.getCreatedAt());
            return ResponseEntity.ok(dto);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Export all chart states as JSON (ADMIN only).
     * GET /api/chart-state/export
     */
    @GetMapping("/chart-state/export")
    public ResponseEntity<?> exportAll() {
        try {
            var allStates = repository.findAll();
            var exportList = allStates.stream().map(ent -> {
                try {
                    Map<String, Object> overlays = objectMapper.readValue(ent.getOverlaysJson(), Map.class);
                    Map<String, Object> meta = ent.getMetaJson() == null ? Map.of() : objectMapper.readValue(ent.getMetaJson(), Map.class);
                    return new ChartStateDTO(ent.getSymbol(), ent.getPeriod(), overlays, meta, ent.getCreatedAt());
                } catch (Exception e) {
                    return null;
                }
            }).filter(dto -> dto != null).toList();

            return ResponseEntity.ok(exportList);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Import chart states from JSON (ADMIN only).
     * POST /api/chart-state/import
     * Body: array of ChartStateDTO objects
     */
    @PostMapping("/chart-state/import")
    public ResponseEntity<?> importAll(@RequestBody java.util.List<Map<String, Object>> importData) {
        try {
            int imported = 0;
            for (Map<String, Object> item : importData) {
                try {
                    String symbol = (String) item.get("symbol");
                    String period = (String) item.get("period");
                    Object overlaysObj = item.get("overlays");
                    Object metaObj = item.get("meta");

                    if (symbol == null || period == null) continue;

                    String overlaysJson = overlaysObj == null ? "{}" : objectMapper.writeValueAsString(overlaysObj);
                    String metaJson = metaObj == null ? null : objectMapper.writeValueAsString(metaObj);

                    // Check if already exists, update if so
                    UserChartState existing = repository.findTopBySymbolAndPeriodOrderByCreatedAtDesc(symbol, period);
                    if (existing != null) {
                        existing.setOverlaysJson(overlaysJson);
                        existing.setMetaJson(metaJson);
                        existing.setCreatedAt(LocalDateTime.now());
                        repository.save(existing);
                    } else {
                        UserChartState newState = UserChartState.builder()
                                .symbol(symbol)
                                .period(period)
                                .overlaysJson(overlaysJson)
                                .metaJson(metaJson)
                                .createdAt(LocalDateTime.now())
                                .build();
                        repository.save(newState);
                    }
                    imported++;
                } catch (Exception e) {
                    // Skip invalid entries
                }
            }

            return ResponseEntity.ok(Map.of("imported", imported, "total", importData.size()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}
