package com.dtech.kitecon.simulation;

import com.dtech.kitecon.simulation.db.SimulationPersistenceService;
import com.dtech.kitecon.simulation.db.SimulationRun;
import com.dtech.kitecon.simulation.db.SimulationTrade;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * REST endpoints for accessing persisted simulation results.
 * Primary source: database (via SimulationPersistenceService)
 * Fallback source: JSON files from /tmp/sim-results/ directory
 */
@RestController
@RequestMapping("/api/simulation-results")
@Slf4j
public class SimulationViewerController {

    private static final Path RESULTS_DIR = Paths.get("/tmp/sim-results");
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private SimulationPersistenceService persistenceService;

    /**
     * GET /api/simulation-results
     * Returns metadata of all available simulation runs.
     * Merges DB runs + JSON-only runs (union), deduped by run_id, DB takes precedence.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> listRuns() {
        try {
            // DB-only listing. JSON-based runs are intentionally excluded — they bloat
            // the browser on large trades arrays. Use the backfill test to import
            // a JSON run into DB if you want it visible here.
            List<Map<String, Object>> rows = new ArrayList<>();
            List<SimulationRun> dbRuns = persistenceService.findAllRuns();
            if (dbRuns != null) {
                for (SimulationRun run : dbRuns) {
                    Map<String, Object> dto = runToDto(run);
                    dto.put("source", "database");
                    rows.add(dto);
                }
            }

            // Sort by created_at desc
            rows.sort((a, b) -> {
                Object aDate = a.get("created_at");
                Object bDate = b.get("created_at");
                if (aDate != null && bDate != null) {
                    return ((Comparable) bDate).compareTo(aDate);
                }
                return 0;
            });

            Map<String, Object> response = new HashMap<>();
            response.put("runs", rows);
            response.put("source", "database");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error fetching runs: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to fetch runs");
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * GET /api/simulation-results/{runId}
     * Returns metadata for a specific run (DB first, JSON fallback).
     */
    @GetMapping(value = "/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getRun(@PathVariable String runId) {
        try {
            Optional<SimulationRun> optRun = persistenceService.findByRunId(runId);
            if (optRun.isPresent()) {
                Map<String, Object> response = runToDto(optRun.get());
                response.put("source", "database");
                return ResponseEntity.ok(response);
            }
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error fetching run {}: {}", runId, e.getMessage(), e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * GET /api/simulation-results/{runId}/trades?page=0&size=100
     * Returns paginated trades for a run (DB first, JSON fallback returns all trades in one page).
     */
    @GetMapping(value = "/{runId}/trades", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getTrades(
            @PathVariable String runId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        try {
            Optional<SimulationRun> optRun = persistenceService.findByRunId(runId);
            if (optRun.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            Long runDbId = optRun.get().getId();
            Pageable pageable = PageRequest.of(page, size);
            Page<SimulationTrade> trades = persistenceService.getTrades(runDbId, pageable);

            Map<String, Object> response = new HashMap<>();
            response.put("content", trades.getContent().stream()
                    .map(this::tradeToDto)
                    .toList());
            response.put("totalElements", trades.getTotalElements());
            response.put("totalPages", trades.getTotalPages());
            response.put("number", trades.getNumber());
            response.put("size", trades.getSize());
            response.put("source", "database");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error fetching trades for run {}: {}", runId, e.getMessage(), e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * GET /api/simulation-results/{runId}/trades/{tradeId}
     * Returns a single trade (DB first with bars_around from Candle table, JSON fallback without bars).
     */
    @GetMapping(value = "/{runId}/trades/{tradeId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getTrade(
            @PathVariable String runId,
            @PathVariable Long tradeId) {
        try {
            // Try DB first
            Optional<SimulationRun> optRun = persistenceService.findByRunId(runId);
            if (optRun.isPresent()) {
                Optional<SimulationTrade> optTrade = persistenceService.getTrade(optRun.get().getId(), tradeId);
                if (optTrade.isPresent()) {
                    SimulationTrade trade = optTrade.get();
                    Map<String, Object> response = tradeToDto(trade);

                    // Fetch bars_around from Candle table
                    // Window: entry_time - 100 hours to exit_time + 50 hours
                    java.time.Instant startTime = trade.getEntryTime().minusSeconds(100L * 3600);
                    java.time.Instant endTime = trade.getExitTime().plusSeconds(50L * 3600);
                    List<Map<String, Object>> bars = persistenceService.getBarsAround(
                            trade.getSymbol(), startTime, endTime);

                    response.put("bars_around", bars);
                    response.put("bars_around_count", bars.size());
                    response.put("source", "database");

                    return ResponseEntity.ok(response);
                }
            }

            return ResponseEntity.notFound().build();

        } catch (Exception e) {
            log.error("Error fetching trade {}/{}: {}", runId, tradeId, e.getMessage(), e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * POST /api/simulation-results/backfill/{runId}
     * Reads a JSON file and persists its trades to the database.
     */
    @PostMapping(value = "/backfill/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> backfillRun(@PathVariable String runId) {
        try {
            // Check if run already exists
            if (persistenceService.findByRunId(runId).isPresent()) {
                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("error", "Run already exists");
                response.put("runId", runId);
                return ResponseEntity.status(409).body(response);
            }

            Path runFile = RESULTS_DIR.resolve(runId + ".json");
            if (!Files.exists(runFile)) {
                return ResponseEntity.notFound().build();
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> jsonData = objectMapper.readValue(runFile.toFile(), Map.class);

            String strategyName = (String) jsonData.get("strategy_name");
            String timeframe = (String) jsonData.get("timeframe");
            Integer stocksCount = ((Number) jsonData.get("stocks_count")).intValue();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> trades = (List<Map<String, Object>>) jsonData.get("trades");

            if (trades == null || trades.isEmpty()) {
                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("error", "No trades found in JSON file");
                response.put("runId", runId);
                return ResponseEntity.status(400).body(response);
            }

            // Persist the run and its trades
            SimulationRun persistedRun = persistenceService.persistRun(
                    runId,
                    strategyName,
                    timeframe,
                    stocksCount,
                    trades,
                    new HashMap<>()  // empty extra metadata
            );

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("run_id", runId);
            response.put("trades_inserted", trades.size());
            response.put("status", "ok");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error backfilling run {}: {}", runId, e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    // ============= JSON Fallback Methods =============

    /**
     * Fallback method to read the _index.json file.
     */
    private ResponseEntity<Map<String, Object>> getIndexFromJson() {
        try {
            Path indexFile = RESULTS_DIR.resolve("_index.json");
            if (!Files.exists(indexFile)) {
                Map<String, Object> empty = new HashMap<>();
                empty.put("runs", new ArrayList<>());
                empty.put("source", "default-empty");
                return ResponseEntity.ok(empty);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> index = objectMapper.readValue(indexFile.toFile(), Map.class);
            index.put("source", "json-file");
            return ResponseEntity.ok(index);

        } catch (IOException e) {
            log.error("Error reading index.json: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to read index file");
            return ResponseEntity.status(500).body(error);
        }
    }

    // ============= DTO Converters =============

    /**
     * Convert SimulationRun entity to DTO map.
     */
    private Map<String, Object> runToDto(SimulationRun run) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", run.getId());
        dto.put("run_id", run.getRunId());
        dto.put("strategy_name", run.getStrategyName());
        dto.put("timeframe", run.getTimeframe());
        dto.put("stocks_count", run.getStocksCount());
        dto.put("total_trades", run.getTotalTrades());
        dto.put("wins", run.getWins());
        dto.put("losses", run.getLosses());
        dto.put("total_pnl_pct", run.getTotalPnlPct());
        dto.put("created_at", run.getCreatedAt());
        return dto;
    }

    /**
     * Convert SimulationTrade entity to DTO map.
     */
    private Map<String, Object> tradeToDto(SimulationTrade trade) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", trade.getId());
        dto.put("symbol", trade.getSymbol());
        dto.put("pattern_type", trade.getPatternType());
        dto.put("direction", trade.getDirection());
        dto.put("entry_bar", trade.getEntryBar());
        dto.put("entry_time", trade.getEntryTime());
        dto.put("entry_price", trade.getEntryPrice());
        dto.put("stop_initial", trade.getStopInitial());
        dto.put("target_initial", trade.getTargetInitial());
        dto.put("exit_bar", trade.getExitBar());
        dto.put("exit_time", trade.getExitTime());
        dto.put("exit_price", trade.getExitPrice());
        dto.put("exit_reason", trade.getExitReason());
        dto.put("pnl_pct", trade.getPnlPct());
        dto.put("was_winner", trade.getWasWinner());
        dto.put("holding_bars", trade.getHoldingBars());

        // Deserialize JSON fields if present
        if (trade.getPatternPivots() != null) {
            try {
                dto.put("pattern_pivots", objectMapper.readValue(trade.getPatternPivots(), Object.class));
            } catch (Exception e) {
                log.warn("Failed to deserialize pattern_pivots: {}", e.getMessage());
                dto.put("pattern_pivots", null);
            }
        }

        if (trade.getTriggerMeta() != null) {
            try {
                dto.put("trigger_meta", objectMapper.readValue(trade.getTriggerMeta(), Object.class));
            } catch (Exception e) {
                log.warn("Failed to deserialize trigger_meta: {}", e.getMessage());
                dto.put("trigger_meta", null);
            }
        }

        return dto;
    }
}
