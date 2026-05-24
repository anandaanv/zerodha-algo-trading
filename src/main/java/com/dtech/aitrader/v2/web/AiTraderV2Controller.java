package com.dtech.aitrader.v2.web;

import com.dtech.aitrader.data.PlanGroup;
import com.dtech.aitrader.data.PlanGroupState;
import com.dtech.aitrader.data.WatchTrade;
import com.dtech.aitrader.repository.PlanGroupRepository;
import com.dtech.aitrader.repository.WatchTradeRepository;
import com.dtech.aitrader.v2.batch.NightlyBundleDumpService;
import com.dtech.aitrader.v2.narrative.bundle.NarrativeBundleDumpService;
import com.dtech.aitrader.v2.orchestrator.OrchestratorV2Service;
import com.dtech.aitrader.v2.pivots.PivotBundleDumpService;
import com.dtech.aitrader.v2.regime.eval.GateEvalLoop;
import com.dtech.aitrader.v2.regime.queue.NightlyQueueSeeder;
import com.dtech.aitrader.v2.regime.reader.RegimeRecordReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoints to fire an Agent 1 scan and read back the resulting plan_groups + branches.
 * Browser-friendly so the trader can hit them from /ai-trader during the build phase.
 */
@RestController
@RequestMapping("/api/ai-trader-v2")
@RequiredArgsConstructor
@Slf4j
public class AiTraderV2Controller {

    private final OrchestratorV2Service orchestrator;
    private final NightlyBundleDumpService nightlyBatch;
    private final NarrativeBundleDumpService narrativeBatch;
    private final PivotBundleDumpService pivotBatch;
    private final NightlyQueueSeeder queueSeeder;
    private final RegimeRecordReader regimeReader;
    private final GateEvalLoop gateEval;
    private final PlanGroupRepository planGroupRepository;
    private final WatchTradeRepository watchTradeRepository;

    @PostMapping("/scan")
    public ResponseEntity<?> scan(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "OneHour") String timeframe,
            @RequestParam(required = false) String tabUuid,
            Authentication auth) {
        Long userId = extractUserId(auth);
        try {
            OrchestratorV2Service.ScanResult result = orchestrator.scan(userId, symbol, timeframe, tabUuid);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("v2 scan failed for symbol={}: {}", symbol, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Fires the nightly bundle-dump batch on demand. Same code path as the scheduled cron;
     * useful for testing / running a fresh batch before the next 22:00 IST cycle.
     */
    @PostMapping("/batch/run")
    public ResponseEntity<?> runBatch(
            @RequestParam(required = false) String symbols,
            Authentication auth) {
        try {
            NightlyBundleDumpService.Summary summary = nightlyBatch.runManual(symbols);
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            log.error("manual v2 batch fire failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Fires the narrative-bundle dump on demand — builds compact multi-indicator narratives for
     * a list of symbols across multiple timeframes and writes one memsys memory per (symbol, TF)
     * plus a summary memory. Mirrors {@link #runBatch} but uploads a different bundle type
     * ({@code mtf-runup-v2} tag, see owner b3ff4ca0).
     *
     * <p>Query params (all optional):
     * <ul>
     *   <li>{@code symbols} — CSV override; defaults to {@code narrative.batch.symbols} property
     *       (owner's 15 stocks).</li>
     *   <li>{@code timeframes} — CSV TF override; defaults to {@code Week,Day,OneHour,FifteenMinute}.</li>
     *   <li>{@code dateLabel} — ISO date used in the {@code date-} tag; defaults to today (IST).</li>
     * </ul>
     */
    @PostMapping("/narrative-bundle/run")
    public ResponseEntity<?> runNarrativeBatch(
            @RequestParam(required = false) String symbols,
            @RequestParam(required = false) String timeframes,
            @RequestParam(required = false) String dateLabel,
            Authentication auth) {
        try {
            NarrativeBundleDumpService.Summary summary =
                    narrativeBatch.runManual(symbols, timeframes, dateLabel);
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            log.error("manual narrative-bundle fire failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Pivot-bundle sweep — for each symbol, runs ZigZagService over all 4 TFs, writes one
     * {@code pivot-bundle} memsys memory per symbol with JSON body. Pass {@code symbols=__FNO__}
     * to sweep the full FNO universe. Tag scheme:
     * {@code [ai-trader-v2, pivot-bundle, symbol-<SYM>, date-<DATE>, asof-<DATE>]}.
     */
    @PostMapping("/pivot-bundle/run")
    public ResponseEntity<?> runPivotBatch(
            @RequestParam(required = false) String symbols,
            @RequestParam(required = false) String timeframes,
            @RequestParam(required = false) String dateLabel,
            Authentication auth) {
        try {
            PivotBundleDumpService.Summary summary =
                    pivotBatch.runManual(symbols, timeframes, dateLabel);
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            log.error("manual pivot-bundle fire failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Component 1 trigger — seed the F&O scan queue for the given date (defaults to today, IST).
     * Idempotent: re-runs skip symbols that already have a pending marker.
     * See {@code docs/regime-records.md} for the tag contract.
     */
    @PostMapping("/queue-seeder/run")
    public ResponseEntity<?> runQueueSeeder(
            @RequestParam(required = false) String dateLabel,
            Authentication auth) {
        try {
            NightlyQueueSeeder.Summary summary = queueSeeder.runManual(dateLabel);
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            log.error("manual queue-seeder fire failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Component 2 trigger — read and validate today's regime records. Returns the validated
     * watchlist + rejected-record details for audit. ADVISORY ONLY — strategy layer applies its
     * own entry rule, sizing, and risk caps.
     */
    @GetMapping("/regime-reader/read")
    public ResponseEntity<?> readRegime(
            @RequestParam(required = false) String dateLabel,
            @RequestParam(required = false) String sourceRun,
            Authentication auth) {
        try {
            RegimeRecordReader.Result result = regimeReader.read(dateLabel, sourceRun);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("regime-reader read failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Component 3 trigger — score regime records from N days ago against subsequent price action,
     * aggregate by (regime × conviction), write the aggregate as a memsys {@code gate-eval} memory.
     */
    @PostMapping("/gate-eval/run")
    public ResponseEntity<?> runGateEval(
            @RequestParam(required = false) String dateLabel,
            Authentication auth) {
        try {
            GateEvalLoop.Summary summary = gateEval.runManual(dateLabel);
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            log.error("gate-eval run failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/plan-groups")
    public ResponseEntity<?> listPlanGroups(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String state,
            Authentication auth) {
        Long userId = extractUserId(auth);
        List<PlanGroup> rows;
        if (symbol != null && state != null) {
            rows = planGroupRepository.findByUserIdAndSymbolAndState(
                    userId, symbol.toUpperCase(), PlanGroupState.valueOf(state.toUpperCase()));
        } else if (symbol != null) {
            rows = planGroupRepository.findByUserIdAndSymbolAndState(
                    userId, symbol.toUpperCase(), PlanGroupState.WATCHING);
        } else if (state != null) {
            rows = planGroupRepository.findByUserIdAndStateOrderByUpdatedAtDesc(
                    userId, PlanGroupState.valueOf(state.toUpperCase()));
        } else {
            rows = planGroupRepository.findByUserIdAndStateOrderByUpdatedAtDesc(userId, PlanGroupState.WATCHING);
        }
        return ResponseEntity.ok(rows.stream().map(this::toDto).toList());
    }

    @GetMapping("/plan-groups/{id}")
    public ResponseEntity<?> getPlanGroup(@PathVariable Long id, Authentication auth) {
        Long userId = extractUserId(auth);
        return planGroupRepository.findById(id)
                .filter(pg -> pg.getUserId().equals(userId))
                .map(pg -> {
                    Map<String, Object> dto = toDto(pg);
                    List<WatchTrade> branches = watchTradeRepository.findAll().stream()
                            .filter(wt -> id.equals(wt.getPlanGroupId()))
                            .toList();
                    dto.put("branches", branches);
                    return ResponseEntity.ok(dto);
                })
                .orElseGet(() -> ResponseEntity.status(404).body(null));
    }

    private Map<String, Object> toDto(PlanGroup pg) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", pg.getId());
        dto.put("symbol", pg.getSymbol());
        dto.put("timeframe", pg.getTimeframe());
        dto.put("state", pg.getState());
        dto.put("underlying_hypothesis", pg.getUnderlyingHypothesis());
        dto.put("structural_validation", pg.getStructuralValidation());
        dto.put("decision_zone", Map.of(
                "low", pg.getDecisionZoneLow(),
                "high", pg.getDecisionZoneHigh(),
                "rationale", pg.getDecisionZoneRationale()
        ));
        dto.put("valid_until", pg.getValidUntil());
        dto.put("created_at", pg.getCreatedAt());
        dto.put("agent_version", pg.getAgentVersion());
        return dto;
    }

    private Long extractUserId(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new IllegalStateException("User not authenticated");
        }
        return 1L; // matches existing pattern in AiLevelsController
    }
}
