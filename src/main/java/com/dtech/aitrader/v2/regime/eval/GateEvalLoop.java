package com.dtech.aitrader.v2.regime.eval;

import com.dtech.aitrader.v2.memsys.MemsysClient;
import com.dtech.aitrader.v2.memsys.MemsysMemory;
import com.dtech.aitrader.v2.memsys.MemsysWriteResult;
import com.dtech.aitrader.v2.regime.Bias;
import com.dtech.aitrader.v2.regime.Conviction;
import com.dtech.aitrader.v2.regime.DefiningLevels;
import com.dtech.aitrader.v2.regime.RegimeClass;
import com.dtech.aitrader.v2.regime.RegimeRecord;
import com.dtech.aitrader.v2.regime.RegimeRecordValidator;
import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.chartdata.service.ChartDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Component 3 of the watchlist→regime pipeline.
 *
 * <p>Daily evaluation loop: scores regime records written N days ago against subsequent price
 * action. The aggregate hit-rate (by regime class × conviction) is written back to memsys so the
 * gate can be tuned over time. Owner: "This loop is how we learn whether the gate works. Build
 * it early, not last."
 *
 * <h2>Scoring</h2>
 * Each record gets one of four outcomes:
 * <ul>
 *   <li>{@code HIT} — any target_if_resolves[i] level was reached in the direction of bias,
 *       BEFORE invalidation was breached.</li>
 *   <li>{@code INVALIDATED} — invalidation level was breached (in either order with targets, but
 *       checked before HIT when in the same window — conservative interpretation).</li>
 *   <li>{@code PENDING} — neither hit nor invalidated, and {@code valid_until > now}.</li>
 *   <li>{@code MISS} — neither hit nor invalidated, and {@code valid_until ≤ now} (expired
 *       without resolution).</li>
 * </ul>
 *
 * <p>For long bias: HIT when high ≥ target, INVALIDATED when low ≤ invalidation. <br>
 * For short bias: HIT when low ≤ target, INVALIDATED when high ≥ invalidation. <br>
 * For neutral (squeeze_coiled): treated as PENDING (no directional eval) — gate v1 limitation;
 * future: bidirectional resolution check.
 *
 * <h2>Aggregation</h2>
 * Records are grouped by {@code (regime_class, conviction)} and reported as
 * {@code hit / (hit + miss + invalidated)}. Aggregate written as a memsys {@code fact} memory.
 *
 * <p>Config:
 * <ul>
 *   <li>{@code regime.eval.enabled} — master switch (default false)</li>
 *   <li>{@code regime.eval.cron} — Spring cron, IST (default "0 0 19 * * MON-FRI" = 19:00 IST)</li>
 *   <li>{@code regime.eval.user-id} — memsys tenant (default 1)</li>
 *   <li>{@code regime.eval.lookback-days} — how many days back to fetch records (default 3)</li>
 *   <li>{@code regime.eval.max-records} — cap per scan (default 500)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GateEvalLoop {

    private final MemsysClient memsys;
    private final RegimeRecordValidator validator;
    private final ChartDataService chartDataService;

    @Value("${regime.eval.enabled:false}")
    private boolean enabled;

    @Value("${regime.eval.user-id:1}")
    private Long batchUserId;

    @Value("${regime.eval.lookback-days:3}")
    private int lookbackDays;

    @Value("${regime.eval.max-records:500}")
    private int maxRecords;

    /** Scheduled daily fire — 19:00 IST. Guarded by {@code regime.eval.enabled}. */
    @Scheduled(cron = "${regime.eval.cron:0 0 19 * * MON-FRI}", zone = "Asia/Kolkata")
    public void scheduledRun() {
        if (!enabled) {
            log.debug("[gate-eval] skipped: regime.eval.enabled=false");
            return;
        }
        runManual(null);
    }

    /**
     * Manual fire. {@code dateLabel} is the date this aggregate is FOR (the eval-run date, used
     * in the output tag). The records being scored are from {@code lookbackDays} days BEFORE
     * this. Defaults to today (IST).
     */
    public Summary runManual(String dateLabel) {
        Instant start = Instant.now();
        if (dateLabel == null || dateLabel.isBlank()) {
            dateLabel = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
        }
        LocalDate target = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(lookbackDays);
        String recordsDateTag = target.toString();
        log.info("[gate-eval] start eval_date={} scoring_records_dated={} lookback_days={}",
                dateLabel, recordsDateTag, lookbackDays);

        // 1. Fetch the records to score.
        List<MemsysMemory> hits;
        try {
            hits = memsys.searchMemories(
                    batchUserId,
                    "regime record watchlist",
                    List.of("ai-trader-v2", "regime-record", "watchlist", "date-" + recordsDateTag),
                    /*type*/ null,
                    /*parentId*/ null,
                    /*since*/ null,
                    /*until*/ null,
                    /*limit*/ maxRecords);
        } catch (Exception e) {
            log.error("[gate-eval] memsys search failed: {}", e.getMessage());
            return new Summary(dateLabel, recordsDateTag, 0, 0, 0, 0, 0, 0, 0,
                    Map.of(), List.of(),
                    Duration.between(start, Instant.now()).toMillis(),
                    e.getMessage(), null);
        }
        if (hits == null) hits = List.of();

        // 2. Score each record.
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Kolkata"));
        List<PerRecordOutcome> outcomes = new ArrayList<>();
        int parseFailed = 0;
        for (MemsysMemory mem : hits) {
            String memId = mem.getId();
            RegimeRecordValidator.Result vr = validator.parse(mem.getContent(), memId);
            if (vr instanceof RegimeRecordValidator.Invalid inv) {
                parseFailed++;
                log.warn("[gate-eval] {} unscored — validation failed: {}", memId, inv.errors());
                continue;
            }
            RegimeRecord r = ((RegimeRecordValidator.Valid) vr).record();
            outcomes.add(scoreOne(memId, r, now));
        }

        // 3. Aggregate by (regime, conviction).
        Map<String, AggregateBucket> buckets = aggregate(outcomes);

        // 4. Write aggregate memory.
        String body = renderAggregate(dateLabel, recordsDateTag, hits.size(), parseFailed,
                outcomes, buckets);
        String aggregateMemoryId = writeAggregate(dateLabel, body);

        // 5. Summarise.
        int hit = (int) outcomes.stream().filter(o -> o.outcome() == Outcome.HIT).count();
        int miss = (int) outcomes.stream().filter(o -> o.outcome() == Outcome.MISS).count();
        int invalidated = (int) outcomes.stream().filter(o -> o.outcome() == Outcome.INVALIDATED).count();
        int pending = (int) outcomes.stream().filter(o -> o.outcome() == Outcome.PENDING).count();
        int skippedNeutral = (int) outcomes.stream().filter(o -> o.outcome() == Outcome.SKIPPED_NEUTRAL).count();

        long elapsedMs = Duration.between(start, Instant.now()).toMillis();
        Summary s = new Summary(dateLabel, recordsDateTag, hits.size(), parseFailed,
                hit, miss, invalidated, pending, skippedNeutral, buckets, outcomes, elapsedMs,
                null, aggregateMemoryId);
        log.info("[gate-eval] done eval_date={} records_dated={} found={} parseFailed={} hit={} miss={} inv={} pending={} neutralSkipped={} aggregate={}",
                dateLabel, recordsDateTag, hits.size(), parseFailed, hit, miss,
                invalidated, pending, skippedNeutral, aggregateMemoryId);
        return s;
    }

    private PerRecordOutcome scoreOne(String memId, RegimeRecord r, OffsetDateTime now) {
        if (r.getBias() == Bias.NEUTRAL) {
            return new PerRecordOutcome(memId, r.getSymbol(), r.getRegime(), r.getBias(),
                    r.getConviction(), Outcome.SKIPPED_NEUTRAL,
                    "neutral bias — bidirectional eval not implemented in v1", null, null);
        }
        DefiningLevels lv = r.getDefining_levels();
        if (lv == null || lv.getInvalidation() == null) {
            // Validator should have caught this; defensive.
            return new PerRecordOutcome(memId, r.getSymbol(), r.getRegime(), r.getBias(),
                    r.getConviction(), Outcome.SKIPPED_NEUTRAL,
                    "missing defining_levels.invalidation", null, null);
        }
        double invalidation = lv.getInvalidation();
        List<Double> targets = lv.getTargets_if_resolves();
        if (targets == null || targets.isEmpty()) {
            return new PerRecordOutcome(memId, r.getSymbol(), r.getRegime(), r.getBias(),
                    r.getConviction(), Outcome.PENDING,
                    "no targets_if_resolves to test", null, null);
        }

        // Fetch bars from as_of → now (IST). Daily TF is the resolution-grain default.
        long fromEpoch = r.getAs_of().toEpochSecond();
        List<OhlcBarDTO> bars;
        try {
            bars = chartDataService.getBars(r.getSymbol(), "Day", fromEpoch, null, /*fetchLatest*/ true);
        } catch (Exception e) {
            log.warn("[gate-eval] {} {} price fetch failed: {}", memId, r.getSymbol(), e.getMessage());
            return new PerRecordOutcome(memId, r.getSymbol(), r.getRegime(), r.getBias(),
                    r.getConviction(), Outcome.PENDING,
                    "price fetch failed: " + e.getMessage(), null, null);
        }
        if (bars == null || bars.isEmpty()) {
            return new PerRecordOutcome(memId, r.getSymbol(), r.getRegime(), r.getBias(),
                    r.getConviction(), Outcome.PENDING,
                    "no bars since as_of", null, null);
        }

        // Walk bars chronologically. First terminal event wins (conservative).
        boolean isLong = r.getBias() == Bias.LONG;
        Double hitTarget = null;
        Double invHitPrice = null;
        OffsetDateTime hitOn = null;
        for (OhlcBarDTO b : bars) {
            // Invalidation check first (conservative interpretation per scoring rules).
            if (isLong ? b.getLow() <= invalidation : b.getHigh() >= invalidation) {
                invHitPrice = isLong ? b.getLow() : b.getHigh();
                hitOn = OffsetDateTime.ofInstant(Instant.ofEpochSecond(b.getTime()),
                        ZoneId.of("Asia/Kolkata"));
                break;
            }
            // Target check — first target hit wins.
            for (Double t : targets) {
                if (t == null) continue;
                if (isLong ? b.getHigh() >= t : b.getLow() <= t) {
                    hitTarget = t;
                    hitOn = OffsetDateTime.ofInstant(Instant.ofEpochSecond(b.getTime()),
                            ZoneId.of("Asia/Kolkata"));
                    break;
                }
            }
            if (hitTarget != null) break;
        }

        if (invHitPrice != null) {
            return new PerRecordOutcome(memId, r.getSymbol(), r.getRegime(), r.getBias(),
                    r.getConviction(), Outcome.INVALIDATED,
                    String.format("invalidation %.2f breached at %.2f", invalidation, invHitPrice),
                    invHitPrice, hitOn);
        }
        if (hitTarget != null) {
            return new PerRecordOutcome(memId, r.getSymbol(), r.getRegime(), r.getBias(),
                    r.getConviction(), Outcome.HIT,
                    String.format("target %.2f reached", hitTarget), hitTarget, hitOn);
        }
        // Neither hit. Pending or miss based on valid_until.
        Outcome out = (r.getValid_until() != null && r.getValid_until().isAfter(now))
                ? Outcome.PENDING : Outcome.MISS;
        return new PerRecordOutcome(memId, r.getSymbol(), r.getRegime(), r.getBias(),
                r.getConviction(), out,
                "neither target nor invalidation reached in window",
                null, null);
    }

    private Map<String, AggregateBucket> aggregate(List<PerRecordOutcome> outcomes) {
        Map<String, AggregateBucket> buckets = new TreeMap<>();
        for (PerRecordOutcome o : outcomes) {
            String key = (o.regime() == null ? "?" : o.regime().getWire())
                    + "|" + (o.conviction() == null ? "?" : o.conviction().getWire());
            AggregateBucket b = buckets.computeIfAbsent(key,
                    k -> new AggregateBucket(o.regime(), o.conviction(), 0, 0, 0, 0, 0, 0));
            buckets.put(key, b.add(o.outcome()));
        }
        return buckets;
    }

    private String renderAggregate(String evalDate, String recordsDate, int found,
                                    int parseFailed, List<PerRecordOutcome> outcomes,
                                    Map<String, AggregateBucket> buckets) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Gate eval — eval_date=").append(evalDate)
                .append("  scoring records dated=").append(recordsDate)
                .append("  lookback_days=").append(lookbackDays).append("\n\n");
        sb.append("records_found=").append(found)
                .append("  parse_failed=").append(parseFailed)
                .append("  scored=").append(outcomes.size()).append("\n\n");

        sb.append("## Aggregate (hit / (hit+miss+invalidated) — pending excluded)\n");
        sb.append("regime|conviction|n|hit|miss|inv|pending|neutral_skip|hit_rate\n");
        for (AggregateBucket b : buckets.values()) {
            int settled = b.hit() + b.miss() + b.invalidated();
            String hr = settled > 0 ? String.format("%.0f%%", 100.0 * b.hit() / settled) : "n/a";
            sb.append(b.regime() == null ? "?" : b.regime().getWire()).append("|")
                    .append(b.conviction() == null ? "?" : b.conviction().getWire()).append("|")
                    .append(b.total()).append("|")
                    .append(b.hit()).append("|")
                    .append(b.miss()).append("|")
                    .append(b.invalidated()).append("|")
                    .append(b.pending()).append("|")
                    .append(b.neutralSkipped()).append("|")
                    .append(hr).append("\n");
        }

        sb.append("\n## Per-record outcomes (for audit)\n");
        sb.append("symbol|regime|bias|conviction|outcome|detail|hit_price|hit_on\n");
        outcomes.stream()
                .sorted(Comparator.comparing(PerRecordOutcome::symbol))
                .forEach(o -> sb.append(o.symbol()).append("|")
                        .append(o.regime() == null ? "?" : o.regime().getWire()).append("|")
                        .append(o.bias() == null ? "?" : o.bias().getWire()).append("|")
                        .append(o.conviction() == null ? "?" : o.conviction().getWire()).append("|")
                        .append(o.outcome()).append("|")
                        .append(o.detail() == null ? "" : o.detail()).append("|")
                        .append(o.hitPrice() == null ? "" : String.format("%.2f", o.hitPrice())).append("|")
                        .append(o.hitOn() == null ? "" : o.hitOn().toLocalDate()).append("\n"));
        return sb.toString();
    }

    private String writeAggregate(String dateLabel, String body) {
        List<String> tags = List.of("ai-trader-v2", "gate-eval", "date-" + dateLabel);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("eval_date", dateLabel);
        meta.put("lookback_days", lookbackDays);
        try {
            MemsysWriteResult result = memsys.writeMemory(
                    batchUserId, body, "fact", tags, meta,
                    null, null, /*forceNew*/ true, /*indexable*/ true, null);
            log.info("[gate-eval] wrote aggregate id={} date={} body_chars={}",
                    result.getId(), dateLabel, body.length());
            return result.getId();
        } catch (Exception e) {
            log.error("[gate-eval] aggregate write failed: {}", e.getMessage());
            return null;
        }
    }

    /** Outcome category per record. */
    public enum Outcome { HIT, MISS, INVALIDATED, PENDING, SKIPPED_NEUTRAL }

    public record PerRecordOutcome(
            String memoryId,
            String symbol,
            RegimeClass regime,
            Bias bias,
            Conviction conviction,
            Outcome outcome,
            String detail,
            Double hitPrice,
            OffsetDateTime hitOn
    ) {}

    public record AggregateBucket(
            RegimeClass regime,
            Conviction conviction,
            int total,
            int hit,
            int miss,
            int invalidated,
            int pending,
            int neutralSkipped
    ) {
        AggregateBucket add(Outcome o) {
            return new AggregateBucket(regime, conviction, total + 1,
                    hit + (o == Outcome.HIT ? 1 : 0),
                    miss + (o == Outcome.MISS ? 1 : 0),
                    invalidated + (o == Outcome.INVALIDATED ? 1 : 0),
                    pending + (o == Outcome.PENDING ? 1 : 0),
                    neutralSkipped + (o == Outcome.SKIPPED_NEUTRAL ? 1 : 0));
        }
    }

    public record Summary(
            String evalDate,
            String recordsDate,
            int found,
            int parseFailed,
            int hit,
            int miss,
            int invalidated,
            int pending,
            int skippedNeutral,
            Map<String, AggregateBucket> buckets,
            List<PerRecordOutcome> outcomes,
            long elapsedMs,
            String error,
            String aggregateMemoryId
    ) {}
}
