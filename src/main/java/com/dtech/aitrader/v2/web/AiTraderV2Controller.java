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
import com.dtech.aitrader.v2.rules.BacktestRunner;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.MultiPassEngine;
import com.dtech.aitrader.v2.rules.Rule;
import com.dtech.aitrader.v2.rules.RuleEvalService;
import com.dtech.aitrader.v2.rules.SymbolContext;
import com.dtech.aitrader.v2.rules.scancontext.ScanContextLoader;
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
    private final BacktestRunner ruleBacktest;
    private final RuleEvalService ruleEval;
    private final ScanContextLoader scanContextLoader;
    private final com.dtech.aitrader.v2.rules.patterns.dataload.PatternContextAttacher patternContextAttacher;
    private final MultiPassEngine multiPassEngine;
    private final java.util.List<Rule> allRules;
    private final PlanGroupRepository planGroupRepository;
    private final WatchTradeRepository watchTradeRepository;
    private final com.dtech.kitecon.auth.UserRepository userRepository;

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

    /**
     * Rule-engine pilot backtest — runs every registered {@code Rule} on (symbol, tf) across
     * the given date range, persists firings + outcomes to {@code rule_firing} / {@code firing_outcome}.
     * Per spec {@code f23b95be}: pilot defaults are RELIANCE / Day / 2024-01-01 → 2026-05-22 /
     * windowBars=20.
     */
    @PostMapping("/rule-engine/backtest")
    public ResponseEntity<?> runRuleBacktest(
            @RequestParam(defaultValue = "RELIANCE") String symbol,
            @RequestParam(defaultValue = "Day") String tf,
            @RequestParam(defaultValue = "2024-01-01") String fromDate,
            @RequestParam(defaultValue = "2026-05-22") String toDate,
            @RequestParam(defaultValue = "20") int windowBars,
            Authentication auth) {
        try {
            BacktestRunner.Summary s = ruleBacktest.run(symbol, tf,
                    java.time.LocalDate.parse(fromDate),
                    java.time.LocalDate.parse(toDate),
                    windowBars);
            return ResponseEntity.ok(s);
        } catch (Exception e) {
            log.error("rule-engine backtest failed symbol={}: {}", symbol, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Run EW rules against a real scan-context bundle (owner directive 75b20b10 — test against
     * real data, not enriched fixtures). Loads the scan-context by memsys id, builds a
     * SymbolContext with pivotsByTf + annotations, runs the multi-pass engine, returns the
     * firings as a JSON snapshot.
     */
    @PostMapping("/ew/grade")
    public ResponseEntity<?> gradeEwAgainstScanContext(
            @RequestParam(defaultValue = "RELIANCE") String symbol,
            @RequestParam String scanContextId,
            @RequestParam(required = false) String asOf,
            @RequestParam(name = "userIdOverride", required = false) Long userIdOverride,
            @RequestParam(name = "patternTf", defaultValue = "Day") String patternTf,
            Authentication auth) {
        try {
            Long userId = userIdOverride != null ? userIdOverride : extractUserId(auth);
            java.time.LocalDate effectiveAsOf = asOf == null || asOf.isBlank()
                    ? null
                    : java.time.LocalDate.parse(asOf);
            SymbolContext ctx = scanContextLoader.loadById(userId, scanContextId, symbol, effectiveAsOf);
            if (ctx == null) {
                return ResponseEntity.status(404).body(Map.of(
                        "error", "scan-context not found",
                        "memoryId", scanContextId));
            }
            // Owner 174fbb2a Point 1: pattern data path must populate its own inputs so pattern
            // rules see Day/Hr bars + pivots alongside EW's multi-TF context.
            ctx = patternContextAttacher.attach(ctx, patternTf);
            java.util.List<Firing> firings = multiPassEngine.run(ctx, allRules);

            // Summarise so the response is browsable.
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("symbol", ctx.getSymbol());
            summary.put("as_of", ctx.getAsOf() != null ? ctx.getAsOf().toString() : null);
            summary.put("pivots_by_tf_count", ctx.getPivotsByTf() == null ? Map.of()
                    : ctx.getPivotsByTf().entrySet().stream()
                            .collect(java.util.stream.Collectors.toMap(
                                    Map.Entry::getKey, e -> e.getValue().size())));
            summary.put("annotation_count", ctx.getAnnotations() == null ? 0 : ctx.getAnnotations().size());
            summary.put("firing_count", firings.size());

            // Group by ruleId for the response.
            Map<String, java.util.List<Map<String, Object>>> byRule = new LinkedHashMap<>();
            for (Firing f : firings) {
                byRule.computeIfAbsent(f.getRuleId(), k -> new java.util.ArrayList<>())
                        .add(Map.of(
                                "id", f.getId(),
                                "family", f.getFamily() != null ? f.getFamily().name() : null,
                                "pass", f.getPass() != null ? f.getPass().name() : null,
                                "firesOn", f.getFiresOn() != null ? f.getFiresOn().name() : null,
                                "payload", f.getPayload() != null ? f.getPayload() : Map.of()));
            }
            summary.put("firings_by_rule", byRule);
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            log.error("ew grade failed symbol={} scanContextId={}: {}",
                    symbol, scanContextId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Run the engine against a scan-context body passed directly (no memsys lookup). Useful when
     * the tenant scope makes memsys IDs inaccessible to the calling user (e.g. local-dev runs
     * against bundles produced by a different tenant).
     */
    @PostMapping("/ew/grade-body")
    public ResponseEntity<?> gradeEwAgainstScanContextBody(
            @RequestParam(defaultValue = "RELIANCE") String symbol,
            @RequestParam(required = false) String asOf,
            @RequestParam(name = "patternTf", defaultValue = "Day") String patternTf,
            @org.springframework.web.bind.annotation.RequestBody String markdownBody,
            Authentication auth) {
        try {
            java.time.LocalDate effectiveAsOf = asOf == null || asOf.isBlank()
                    ? null
                    : java.time.LocalDate.parse(asOf);
            com.dtech.aitrader.v2.rules.scancontext.ScanContextParser.ParsedContext parsed =
                    com.dtech.aitrader.v2.rules.scancontext.ScanContextParser.parse(markdownBody);
            java.util.Map<String, java.util.List<com.dtech.kitecon.service.copilot.dto.MarketStructurePoint>> byTf =
                    parsed.getPivotsByTf();
            java.util.List<com.dtech.kitecon.service.copilot.dto.MarketStructurePoint> weekly =
                    byTf.getOrDefault("Week", java.util.List.of());

            SymbolContext ctx = SymbolContext.builder()
                    .symbol(symbol)
                    .asOf(effectiveAsOf != null ? effectiveAsOf : java.time.LocalDate.now())
                    .tf("Week")
                    .pivots(weekly)
                    .pivotsByTf(byTf)
                    .annotations(parsed.getAnnotations())
                    .build();
            // Owner 174fbb2a Point 1: also attach pattern bars + pivots at the chosen TF.
            ctx = patternContextAttacher.attach(ctx, patternTf);

            java.util.List<Firing> firings = multiPassEngine.run(ctx, allRules);

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("symbol", ctx.getSymbol());
            summary.put("as_of", ctx.getAsOf() != null ? ctx.getAsOf().toString() : null);
            summary.put("pivots_by_tf_count", ctx.getPivotsByTf() == null ? Map.of()
                    : ctx.getPivotsByTf().entrySet().stream()
                            .collect(java.util.stream.Collectors.toMap(
                                    Map.Entry::getKey, e -> e.getValue().size())));
            summary.put("annotation_count", ctx.getAnnotations() == null ? 0 : ctx.getAnnotations().size());
            summary.put("firing_count", firings.size());

            Map<String, java.util.List<Map<String, Object>>> byRule = new LinkedHashMap<>();
            for (Firing f : firings) {
                byRule.computeIfAbsent(f.getRuleId(), k -> new java.util.ArrayList<>())
                        .add(Map.of(
                                "id", f.getId() != null ? f.getId() : "",
                                "family", f.getFamily() != null ? f.getFamily().name() : null,
                                "pass", f.getPass() != null ? f.getPass().name() : null,
                                "firesOn", f.getFiresOn() != null ? f.getFiresOn().name() : null,
                                "payload", f.getPayload() != null ? f.getPayload() : Map.of()));
            }
            summary.put("firings_by_rule", byRule);
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            log.error("ew grade-body failed symbol={}: {}", symbol, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/rule-engine/_diag")
    public ResponseEntity<?> ruleEngineDiag(@RequestParam(defaultValue = "RELIANCE") String symbol,
                                              @RequestParam(defaultValue = "Day") String tf) {
        return ResponseEntity.ok(ruleEval.diagnostics(symbol, tf));
    }

    @PostMapping("/rule-engine/_truncate")
    public ResponseEntity<?> ruleEngineTruncate() {
        int n = ruleEval.truncateAll();
        return ResponseEntity.ok(Map.of("deleted", n));
    }

    /**
     * Pilot eval — joins rule_firing × firing_outcome, groups by (rule_id, context_signature),
     * returns hit-rate / MFE / MAE per bucket + the kill-test hit-rate gap per rule.
     */
    @GetMapping("/rule-engine/eval")
    public ResponseEntity<?> runRuleEval(
            @RequestParam(defaultValue = "RELIANCE") String symbol,
            @RequestParam(defaultValue = "Day") String tf,
            @RequestParam(defaultValue = "5") int minN,
            Authentication auth) {
        try {
            RuleEvalService.Result result = ruleEval.evalBySignature(symbol, tf, minN);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("rule-engine eval failed symbol={}: {}", symbol, e.getMessage(), e);
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
        return userRepository.findByUsername(auth.getName())
                .map(com.dtech.kitecon.auth.User::getId)
                .orElseThrow(() -> new IllegalStateException(
                        "User not found in DB: " + auth.getName()));
    }
}
