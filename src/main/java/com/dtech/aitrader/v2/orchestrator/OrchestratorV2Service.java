package com.dtech.aitrader.v2.orchestrator;

import com.dtech.aitrader.data.PlanGroup;
import com.dtech.aitrader.data.PlanGroupState;
import com.dtech.aitrader.data.WatchTrade;
import com.dtech.aitrader.repository.PlanGroupRepository;
import com.dtech.aitrader.repository.WatchTradeRepository;
import com.dtech.aitrader.v2.agent.Agent1Client;
import com.dtech.aitrader.v2.agent.Agent1Output;
import com.dtech.aitrader.v2.scan.Agent1BundleBuilder;
import com.dtech.aitrader.v2.scan.ScanContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Phase 3 orchestrator for AI Trader v2.
 *
 * Per-scan flow (per "Implementation Plan v1.2"):
 *   1. Build the input bundle (drawings, annotations, journal, OHLC, candle patterns,
 *      existing plan_groups). Memsys-sourced sections (playbook rules, active flags,
 *      recently-resolved flags, user inputs since last scan) are empty until per-user
 *      memsys OAuth lands — Agent 1 tolerates this.
 *   2. Invoke Agent 1.
 *   3. For each plan_group action in the response, dual-write to Postgres + memsys.
 *      memsys side is stubbed for now (logs only); Postgres side persists fully so the
 *      trader can review plan_groups + branches in DB / via UI today.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrchestratorV2Service {

    private static final String AGENT_VERSION = "v1.1";
    private static final int DEFAULT_VALID_DAYS = 3;

    private final Agent1BundleBuilder bundleBuilder;
    private final Agent1Client agent1;
    private final PlanGroupRepository planGroupRepository;
    private final WatchTradeRepository watchTradeRepository;
    private final ObjectMapper mapper;

    @Transactional
    public ScanResult scan(Long userId, String symbol, String timeframe, String tabUuid) {
        long t0 = System.currentTimeMillis();
        log.info("[v2-scan] start userId={} symbol={} timeframe={} tab={}",
                userId, symbol, timeframe, tabUuid);

        // 1. Bundle
        Agent1BundleBuilder.Built built = bundleBuilder.build(userId, symbol, timeframe, tabUuid, AGENT_VERSION);
        ScanContext ctx = built.scanContext();

        // 2. Agent 1
        Agent1Client.Agent1Invocation inv = agent1.invoke(userId, built.userMessage());
        if (!inv.ok()) {
            log.warn("[v2-scan] Agent 1 returned no parseable output for symbol={}; skipping action handlers", symbol);
            return new ScanResult(ctx.getScanId(), 0, 0, 0, 0, 0, inv.response().costUsd().doubleValue(),
                    inv.response().modelUsed(), "agent1-parse-fail");
        }
        Agent1Output out = inv.output();

        // 3. Apply each plan_group action
        int created = 0, updated = 0, superseded = 0, retired = 0;
        for (Agent1Output.PlanGroupSpec pg : nullToEmpty(out.getPlan_groups())) {
            String action = pg.getAction() == null ? "CREATE" : pg.getAction().toUpperCase();
            try {
                switch (action) {
                    case "CREATE" -> { handleCreate(pg, userId, symbol, timeframe, ctx, inv); created++; }
                    case "UPDATE" -> { handleUpdate(pg); updated++; }
                    case "SUPERSEDE" -> { handleSupersede(pg, userId, symbol, timeframe, ctx, inv); superseded++; }
                    case "RETIRE" -> { handleRetire(pg); retired++; }
                    default -> log.warn("[v2-scan] unknown action '{}' on plan_group; skipping", action);
                }
            } catch (Exception e) {
                log.error("[v2-scan] action {} failed on plan_group id={}: {}", action, pg.getId(), e.getMessage(), e);
            }
        }

        int flagsEmitted = nullToEmpty(out.getFlags()).size();
        if (flagsEmitted > 0) {
            // TODO: write to memsys as type=question + ai-trader-flag once per-user auth lands.
            // For today's "see trades" milestone, just log so the trader can review in console.
            for (Agent1Output.FlagSpec f : out.getFlags()) {
                log.info("[v2-scan][flag] severity={} category={} title={} details={}",
                        f.getSeverity(), f.getCategory(), f.getTitle(), f.getDetails());
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        log.info("[v2-scan] done symbol={} created={} updated={} superseded={} retired={} flags={} elapsed={}ms cost=${} model={}",
                symbol, created, updated, superseded, retired, flagsEmitted, elapsed, inv.response().costUsd(), inv.response().modelUsed());

        return new ScanResult(ctx.getScanId(), created, updated, superseded, retired, flagsEmitted,
                inv.response().costUsd().doubleValue(), inv.response().modelUsed(), null);
    }

    // ── action handlers ──────────────────────────────────────────────

    private void handleCreate(Agent1Output.PlanGroupSpec spec, Long userId, String symbol, String timeframe,
                              ScanContext ctx, Agent1Client.Agent1Invocation inv) {
        PlanGroup pg = toEntity(spec, userId, symbol, timeframe, null, ctx, inv);
        pg = planGroupRepository.save(pg);
        log.info("[v2-scan][create] plan_group id={} branches={}", pg.getId(),
                spec.getBranches() == null ? 0 : spec.getBranches().size());
        persistBranches(spec, pg.getId(), symbol, userId);
    }

    private void handleUpdate(Agent1Output.PlanGroupSpec spec) {
        if (spec.getId() == null) return;
        Long pgId = tryParseLong(spec.getId());
        if (pgId == null) return;
        Optional<PlanGroup> existing = planGroupRepository.findById(pgId);
        if (existing.isEmpty()) {
            log.warn("[v2-scan][update] plan_group id={} not found; skipping", spec.getId());
            return;
        }
        PlanGroup pg = existing.get();
        if (spec.getUnderlying_hypothesis() != null) pg.setUnderlyingHypothesis(spec.getUnderlying_hypothesis());
        if (spec.getStructural_validation() != null) pg.setStructuralValidation(spec.getStructural_validation());
        if (spec.getDecision_zone() != null) {
            pg.setDecisionZoneLow(toBigDecimal(spec.getDecision_zone().getLow()));
            pg.setDecisionZoneHigh(toBigDecimal(spec.getDecision_zone().getHigh()));
            pg.setDecisionZoneRationale(spec.getDecision_zone().getRationale());
        }
        if (spec.getValid_until() != null) pg.setValidUntil(parseIso(spec.getValid_until()));
        planGroupRepository.save(pg);
        log.info("[v2-scan][update] plan_group id={} applied", pgId);
    }

    private void handleSupersede(Agent1Output.PlanGroupSpec spec, Long userId, String symbol, String timeframe,
                                 ScanContext ctx, Agent1Client.Agent1Invocation inv) {
        Long oldId = tryParseLong(spec.getSupersedes_group_id());
        if (oldId != null) {
            planGroupRepository.findById(oldId).ifPresent(old -> {
                old.setState(PlanGroupState.SUPERSEDED);
                planGroupRepository.save(old);
            });
        }
        PlanGroup pg = toEntity(spec, userId, symbol, timeframe, oldId, ctx, inv);
        pg = planGroupRepository.save(pg);
        log.info("[v2-scan][supersede] new plan_group id={} supersedes old id={}", pg.getId(), oldId);
        persistBranches(spec, pg.getId(), symbol, userId);
    }

    private void handleRetire(Agent1Output.PlanGroupSpec spec) {
        Long pgId = tryParseLong(spec.getId());
        if (pgId == null) return;
        planGroupRepository.findById(pgId).ifPresent(pg -> {
            pg.setState(PlanGroupState.INVALIDATED);
            planGroupRepository.save(pg);
            log.info("[v2-scan][retire] plan_group id={} → INVALIDATED", pgId);
        });
    }

    // ── helpers ──────────────────────────────────────────────────────

    private void persistBranches(Agent1Output.PlanGroupSpec spec, Long planGroupId, String symbol, Long userId) {
        if (spec.getBranches() == null) return;
        for (Agent1Output.BranchSpec b : spec.getBranches()) {
            try {
                WatchTrade wt = WatchTrade.builder()
                        .symbol(symbol)
                        .sourceType("AI_TRADER_V2")
                        .generatedAt(LocalDateTime.now())
                        .generatedForDate(LocalDate.now())
                        .direction(b.getDirection())
                        .entry(toBigDecimal(midpoint(b.getEntry_zone())))
                        .sl(toBigDecimal(b.getStop_loss()))
                        .target(firstTarget(b.getTargets()))
                        .confidence(b.getConfidence())
                        .rr(computeRr(b))
                        .triggerType("PATTERN_AT_ZONE")
                        .triggerSpecJson(safeJson(buildTriggerSpec(b)))
                        .rationale(b.getReasoning())
                        .status("WATCHING")
                        .userId(userId)
                        .planGroupId(planGroupId)
                        .branchLabel(b.getLabel())
                        .siblingKillBranchIdsJson(safeJson(b.getSibling_kill_branches()))
                        .decisionZoneLow(toBigDecimal(b.getEntry_zone() == null || b.getEntry_zone().isEmpty() ? null : b.getEntry_zone().get(0)))
                        .decisionZoneHigh(toBigDecimal(b.getEntry_zone() == null || b.getEntry_zone().size() < 2 ? null : b.getEntry_zone().get(1)))
                        .build();
                watchTradeRepository.save(wt);
            } catch (Exception e) {
                log.error("[v2-scan] failed to persist branch label={}: {}", b.getLabel(), e.getMessage(), e);
            }
        }
    }

    private PlanGroup toEntity(Agent1Output.PlanGroupSpec spec, Long userId, String symbol, String timeframe,
                                Long supersedesId, ScanContext ctx, Agent1Client.Agent1Invocation inv) {
        return PlanGroup.builder()
                .userId(userId)
                .symbol(symbol)
                .timeframe(timeframe)
                .state(PlanGroupState.WATCHING)
                .underlyingHypothesis(spec.getUnderlying_hypothesis())
                .structuralValidation(spec.getStructural_validation())
                .sourceDrawingIdsJson(safeJson(spec.getSource_drawing_ids()))
                .sourceLabelIdsJson(safeJson(spec.getSource_label_ids()))
                .sourceJournalNoteIdsJson(safeJson(spec.getSource_journal_note_ids()))
                .playbookRulesAppliedJson(safeJson(spec.getPlaybook_rules_applied()))
                .decisionZoneLow(spec.getDecision_zone() == null ? null : toBigDecimal(spec.getDecision_zone().getLow()))
                .decisionZoneHigh(spec.getDecision_zone() == null ? null : toBigDecimal(spec.getDecision_zone().getHigh()))
                .decisionZoneRationale(spec.getDecision_zone() == null ? null : spec.getDecision_zone().getRationale())
                .validUntil(spec.getValid_until() != null
                        ? parseIso(spec.getValid_until())
                        : LocalDateTime.now().plusDays(DEFAULT_VALID_DAYS))
                .supersedesGroupId(supersedesId)
                .scanSummary(null) // populated at scan-level from agent output below
                .agentVersion(AGENT_VERSION)
                .rawAgentOutput(rawAgentOutput(inv))
                .memsysMemoryId(null) // TODO: set after memsys-write lands
                .build();
    }

    private String rawAgentOutput(Agent1Client.Agent1Invocation inv) {
        try {
            return mapper.writeValueAsString(inv.output());
        } catch (Exception e) {
            return null;
        }
    }

    private static <T> List<T> nullToEmpty(List<T> list) { return list == null ? List.of() : list; }

    private static Long tryParseLong(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
    }

    private static BigDecimal toBigDecimal(Number n) {
        return n == null ? null : BigDecimal.valueOf(n.doubleValue());
    }

    private static LocalDateTime parseIso(String s) {
        try { return LocalDateTime.parse(s.length() > 19 ? s.substring(0, 19) : s); }
        catch (Exception e) { return LocalDateTime.now().plusDays(DEFAULT_VALID_DAYS); }
    }

    private static Double midpoint(List<Double> zone) {
        if (zone == null || zone.isEmpty()) return null;
        if (zone.size() == 1) return zone.get(0);
        return (zone.get(0) + zone.get(1)) / 2.0;
    }

    private static BigDecimal firstTarget(List<Double> targets) {
        if (targets == null || targets.isEmpty()) return null;
        return BigDecimal.valueOf(targets.get(0));
    }

    private static Double computeRr(Agent1Output.BranchSpec b) {
        if (b.getStop_loss() == null || b.getTargets() == null || b.getTargets().isEmpty()) return null;
        Double entry = midpoint(b.getEntry_zone());
        if (entry == null) return null;
        double risk = Math.abs(entry - b.getStop_loss());
        if (risk < 1e-9) return null;
        double reward = Math.abs(b.getTargets().get(0) - entry);
        return reward / risk;
    }

    private java.util.Map<String, Object> buildTriggerSpec(Agent1Output.BranchSpec b) {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("trigger_condition", b.getTrigger_condition());
        m.put("required_pattern", b.getRequired_pattern());
        m.put("invalidation_level", b.getInvalidation_level());
        m.put("invalidation_rule", b.getInvalidation_rule());
        m.put("targets", b.getTargets());
        return m;
    }

    private String safeJson(Object o) {
        if (o == null) return null;
        try { return mapper.writeValueAsString(o); }
        catch (Exception e) { return null; }
    }

    public record ScanResult(
            String scanId,
            int created,
            int updated,
            int superseded,
            int retired,
            int flagsEmitted,
            double costUsd,
            String modelUsed,
            String error
    ) {}
}
