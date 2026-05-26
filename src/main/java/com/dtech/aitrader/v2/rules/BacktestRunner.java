package com.dtech.aitrader.v2.rules;

import com.dtech.aitrader.data.FiringOutcome;
import com.dtech.aitrader.data.RuleFiring;
import com.dtech.aitrader.repository.FiringOutcomeRepository;
import com.dtech.aitrader.repository.RuleFiringRepository;
import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.chartdata.service.ChartDataService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Orchestrates the multi-pass rule-engine backtest. For each trading day in
 * {@code [fromDate, toDate]} it builds a leakage-guarded {@link SymbolContext}, runs the
 * {@link MultiPassEngine} (passes 0→6) over all registered {@link Rule}s, and persists every
 * emitted {@link Firing} as a {@link RuleFiring} row.
 *
 * <p>A second pass scores outcomes via {@link WalkForwardOutcomeScorer} — but ONLY for
 * {@code fires_on=VERDICT} firings (the only outcome-bearing firing kind per Q7). Intermediate
 * Pass 1-5 firings exist for audit / replay; they are persisted but not outcome-scored.
 *
 * <p>Path A scope: single-round only. The feedback / macro-revisit loop (SPEC-004-A) is layered on
 * later.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BacktestRunner {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final ContextLoader contextLoader;
    private final MultiPassEngine multiPassEngine;
    private final WalkForwardOutcomeScorer outcomeScorer;
    private final ChartDataService chartDataService;
    private final RuleFiringRepository firingRepo;
    private final FiringOutcomeRepository outcomeRepo;

    /** Spring injects every {@link Rule}-typed bean — engine groups by {@code pass()}. */
    private final List<Rule> rules;

    public Summary run(String symbol, String tf, LocalDate fromDate, LocalDate toDate, int windowBars) {
        Instant start = Instant.now();
        log.info("[backtest] {} {} {}→{} rules={} windowBars={}", symbol, tf, fromDate, toDate,
                rules.stream().map(Rule::ruleId).toList(), windowBars);

        TreeSet<LocalDate> tradingDates = tradingDatesIn(symbol, tf, fromDate, toDate);
        if (tradingDates.isEmpty()) {
            log.warn("[backtest] {} {} no bars in range", symbol, tf);
            return new Summary(symbol, tf, fromDate, toDate, 0, 0, 0, 0, 0, 0,
                    Map.of(), Map.of(), Map.of(), 0);
        }

        Map<String, Integer> firingsByRule = new HashMap<>();
        Map<String, Integer> firingsBySignature = new HashMap<>();
        Map<String, Integer> firingsByPass = new HashMap<>();
        int contextsBuilt = 0;
        int firingsWritten = 0;
        int firingsDeduped = 0;     // duplicates rejected by the unique index (re-run idempotency)
        int verdictsWritten = 0;
        int intermediateWritten = 0;

        for (LocalDate asOf : tradingDates) {
            SymbolContext ctx = contextLoader.build(symbol, asOf, tf);
            if (ctx == null) continue;
            contextsBuilt++;

            List<Firing> firings;
            try {
                firings = multiPassEngine.run(ctx, rules);
            } catch (Exception e) {
                log.warn("[backtest] {} {} {} engine threw: {}", symbol, tf, asOf, e.getMessage());
                continue;
            }
            if (firings == null || firings.isEmpty()) continue;

            for (Firing f : firings) {
                try {
                    RuleFiring row = toEntity(f);
                    // O1 idempotency: id IS the digest. With a non-null id, JPA's save() would
                    // call merge() (UPDATE path) which skips @PrePersist and writes null into
                    // created_at. Instead, check existence first and treat existing rows as
                    // deduped. The unique-index on firing_digest remains as a race-condition
                    // backstop (caught below).
                    if (firingRepo.existsById(row.getId())) {
                        firingsDeduped++;
                        continue;
                    }
                    firingRepo.save(row);
                    firingsWritten++;
                    firingsByRule.merge(f.getRuleId(), 1, Integer::sum);
                    String passKey = f.getPass() != null ? f.getPass().name() : "UNKNOWN";
                    firingsByPass.merge(passKey, 1, Integer::sum);
                    if (f.getFiresOn() == FiresOn.VERDICT) {
                        verdictsWritten++;
                        if (f.getContextSignature() != null) {
                            firingsBySignature.merge(f.getContextSignature(), 1, Integer::sum);
                        }
                    } else {
                        intermediateWritten++;
                    }
                } catch (org.springframework.dao.DataIntegrityViolationException dup) {
                    String msg = dup.getMostSpecificCause() != null
                            ? dup.getMostSpecificCause().getMessage()
                            : dup.getMessage();
                    if (msg != null && (msg.contains("Duplicate entry") || msg.contains("UNIQUE"))) {
                        firingsDeduped++;
                    } else {
                        log.warn("[backtest] firing data-integrity (NOT dedup) rule={} as_of={}: {}",
                                f.getRuleId(), f.getAsOf(), msg);
                    }
                } catch (Exception e) {
                    log.warn("[backtest] firing persist failed rule={} as_of={}: {}",
                            f.getRuleId(), f.getAsOf(), e.getMessage());
                }
            }
        }

        int outcomesScored = scoreUnscored(symbol, tf, windowBars);

        long elapsedMs = Duration.between(start, Instant.now()).toMillis();
        Summary s = new Summary(symbol, tf, fromDate, toDate, contextsBuilt,
                firingsWritten, firingsDeduped, verdictsWritten, intermediateWritten,
                outcomesScored, firingsByRule, firingsBySignature, firingsByPass, elapsedMs);
        log.info("[backtest] DONE symbol={} tf={} contexts={} firings={} (deduped={}) verdicts={} intermediates={} outcomes={} elapsedMs={}",
                symbol, tf, contextsBuilt, firingsWritten, firingsDeduped, verdictsWritten,
                intermediateWritten, outcomesScored, elapsedMs);
        return s;
    }

    public int scoreUnscored(String symbol, String tf, int windowBars) {
        List<RuleFiring> pending = firingRepo.findUnscored(symbol, tf);
        int scored = 0;
        for (RuleFiring f : pending) {
            FiringOutcome o = outcomeScorer.score(f, windowBars);
            if (o == null) continue;
            outcomeRepo.save(o);
            scored++;
        }
        return scored;
    }

    private TreeSet<LocalDate> tradingDatesIn(String symbol, String tf,
                                                LocalDate from, LocalDate to) {
        TreeSet<LocalDate> out = new TreeSet<>();
        List<OhlcBarDTO> bars;
        try {
            bars = chartDataService.getBars(symbol, tf, null, null, false);
        } catch (Exception e) {
            log.error("[backtest] {} {} fetch failed: {}", symbol, tf, e.getMessage());
            return out;
        }
        if (bars == null) return out;
        for (OhlcBarDTO b : bars) {
            LocalDate d = LocalDate.ofInstant(Instant.ofEpochSecond(b.getTime()),
                    ZoneId.of("Asia/Kolkata"));
            if (!d.isBefore(from) && !d.isAfter(to)) out.add(d);
        }
        return out;
    }

    /**
     * Map an in-flight {@link Firing} to a persistable {@link RuleFiring} row. Per Q4
     * ({@code 7885ad63}), intermediate (non-VERDICT) firings store NULL in the verdict-shape
     * columns rather than placeholder 0/NEUTRAL — schema is honest. The {@link #fireDigest}
     * column makes re-runs idempotent (Q1).
     */
    private RuleFiring toEntity(Firing f) throws JsonProcessingException {
        // Verdict-shape fields: populated only for VERDICT firings; NULL otherwise.
        boolean isVerdict = f.getFiresOn() == FiresOn.VERDICT;
        RuleFiring.Bias bias = isVerdict ? f.getBias() : null;
        Double trigger = isVerdict ? f.getTriggerPrice() : null;
        Double inval = isVerdict ? f.getInvalidationPrice() : null;
        Double target = isVerdict ? f.getTargetPrice() : null;
        Double finalConv = isVerdict
                ? f.getFinalConviction()
                : (f.getFiresOn() == FiresOn.CANDIDATE ? f.getBasePrior() : null);
        String signature = isVerdict ? f.getContextSignature() : null;

        // Post-O1: MultiPassEngine has already stamped f.getId() with the digest. The
        // firing_digest column duplicates that value — kept for now as an explicit
        // unique-index target until the column is removed in a follow-up cleanup.
        String digest = f.getId();

        return RuleFiring.builder()
                .id(f.getId())
                .ruleId(f.getRuleId())
                .symbol(f.getSymbol())
                .tf(f.getTf())
                .asOf(f.getAsOf())
                .bias(bias)
                .triggerPrice(trigger)
                .invalidationPrice(inval)
                .targetPrice(target)
                .finalConviction(finalConv)
                .convictionComponentsJson(f.getConvictionComponents() == null ? null
                        : JSON.writeValueAsString(f.getConvictionComponents()))
                .contextJson(f.getContext() == null ? null : JSON.writeValueAsString(f.getContext()))
                .contextSignature(signature)
                .evidenceJson(f.getEvidence() == null ? null : JSON.writeValueAsString(f.getEvidence()))
                .family(f.getFamily() != null ? f.getFamily().name() : null)
                .passNum(f.getPass() != null ? f.getPass().order : null)
                .firesOn(f.getFiresOn() != null ? f.getFiresOn().name() : null)
                .refsJson(f.getRefs() == null ? null : JSON.writeValueAsString(f.getRefs()))
                .priorDeltaJson(f.getPriorDelta() == null ? null : JSON.writeValueAsString(
                        priorDeltaMap(f.getPriorDelta())))
                .basePrior(f.getBasePrior())
                .roundNum(f.getRoundNum() != null ? f.getRoundNum() : 1)
                .payloadJson(f.getPayload() == null ? null : JSON.writeValueAsString(f.getPayload()))
                .firingDigest(digest)
                .build();
    }

    /** {@link Map#of} rejects nulls; build the PriorDelta payload as a HashMap that allows them. */
    private static Map<String, Object> priorDeltaMap(PriorDelta d) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("kind", d.kind().name());
        m.put("graduated_delta", d.graduatedDelta());
        m.put("floor_value", d.floorValue());
        m.put("reason", d.reason());
        m.put("rule_ref", d.ruleRef());
        return m;
    }

    @Value
    public static class Summary {
        String symbol;
        String tf;
        LocalDate fromDate;
        LocalDate toDate;
        int contextsBuilt;
        int firingsWritten;
        /** Rows rejected by the unique digest index — expected on idempotent re-runs (Q1). */
        int firingsDeduped;
        int verdictsWritten;
        int intermediateWritten;
        int outcomesScored;
        Map<String, Integer> firingsByRule;
        Map<String, Integer> firingsBySignature;
        Map<String, Integer> firingsByPass;
        long elapsedMs;
    }
}
