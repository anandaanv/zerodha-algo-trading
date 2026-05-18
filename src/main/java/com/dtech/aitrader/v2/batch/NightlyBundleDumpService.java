package com.dtech.aitrader.v2.batch;

import com.dtech.aitrader.v2.scan.Agent1BundleBuilder;
import com.dtech.aitrader.v2.scan.ScanContext;
import com.dtech.aitrader.v2.scan.ScanContextWriter;
import com.dtech.kitecon.repository.IndexSymbolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Nightly batch that builds Agent 1 input bundles for every FNO symbol and posts each one
 * to memsys as an {@code ai-trader-scan-context} memory.
 *
 * <p>It does <strong>NOT</strong> call Agent 1 here — by design. The expensive planning step
 * runs on the user's Max-plan capacity via a claude.ai scheduled routine that reads tonight's
 * bundles from memsys and emits trade memories back. The backend's job is bundle preparation
 * + memsys persistence, free of per-token LLM cost.
 *
 * <p>Config (all overridable per env via application.properties):
 * <ul>
 *   <li>{@code agent1.batch.enabled} — master switch (default false; flip true once memsys auth is in place)</li>
 *   <li>{@code agent1.batch.cron} — Spring cron expression, IST timezone (default "0 0 22 * * MON-FRI")</li>
 *   <li>{@code agent1.batch.user-id} — owner of the scan (default 1)</li>
 *   <li>{@code agent1.batch.timeframe} — analysis timeframe (default "OneHour")</li>
 *   <li>{@code agent1.batch.index} — universe to scan (default "FNO"; column in index_symbol table)</li>
 *   <li>{@code agent1.batch.skip-empty-context} — drop symbols with no user drawings/annotations/journal
 *       (default true — saves routine tokens for symbols Agent 1 will refuse anyway)</li>
 *   <li>{@code agent1.batch.max-symbols} — safety cap on number of writes per fire (default 500)</li>
 * </ul>
 *
 * Manual fire: see {@link com.dtech.aitrader.v2.web.AiTraderV2Controller}'s
 * {@code POST /api/ai-trader-v2/batch/run} endpoint.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NightlyBundleDumpService {

    private static final String AGENT_VERSION = "v1.1";

    private final Agent1BundleBuilder bundleBuilder;
    private final ScanContextWriter scanContextWriter;
    private final IndexSymbolRepository indexSymbolRepository;

    @Value("${agent1.batch.enabled:false}")
    private boolean enabled;

    @Value("${agent1.batch.user-id:1}")
    private Long batchUserId;

    @Value("${agent1.batch.timeframe:OneHour}")
    private String batchTimeframe;

    @Value("${agent1.batch.index:FNO}")
    private String batchIndex;

    @Value("${agent1.batch.skip-empty-context:true}")
    private boolean skipEmptyContext;

    @Value("${agent1.batch.max-symbols:500}")
    private int maxSymbols;

    /**
     * Scheduled fire. Cron is read from {@code agent1.batch.cron} (default 22:00 IST weekdays).
     * Guarded by {@code agent1.batch.enabled} — the cron itself fires every cycle, but the
     * method exits immediately when disabled. This keeps the bean Spring-managed and avoids
     * the conditional-scheduling boilerplate.
     */
    @Scheduled(cron = "${agent1.batch.cron:0 0 22 * * MON-FRI}", zone = "Asia/Kolkata")
    public void scheduledRun() {
        if (!enabled) {
            log.debug("[v2-batch] skipped: agent1.batch.enabled=false");
            return;
        }
        run("scheduled");
    }

    /** Manual fire entrypoint — invoked by the controller's POST /batch/run endpoint. */
    public Summary runManual() {
        return run("manual");
    }

    private Summary run(String trigger) {
        Instant start = Instant.now();
        log.info("[v2-batch] start trigger={} userId={} index={} timeframe={} skipEmpty={}",
                trigger, batchUserId, batchIndex, batchTimeframe, skipEmptyContext);

        List<String> symbols;
        try {
            symbols = indexSymbolRepository.findAllSymbolsByIndexName(batchIndex);
        } catch (Exception e) {
            log.error("[v2-batch] failed to fetch symbol list for index '{}': {}", batchIndex, e.getMessage(), e);
            return new Summary(trigger, 0, 0, 0, 0, Duration.between(start, Instant.now()).toMillis(),
                    "symbol-list-fetch-failed: " + e.getMessage());
        }

        if (symbols == null || symbols.isEmpty()) {
            log.warn("[v2-batch] symbol list for index '{}' is empty — nothing to do", batchIndex);
            return new Summary(trigger, 0, 0, 0, 0, Duration.between(start, Instant.now()).toMillis(),
                    "empty-symbol-list");
        }

        int cap = Math.min(symbols.size(), maxSymbols);
        if (cap < symbols.size()) {
            log.warn("[v2-batch] capping {} → {} symbols (agent1.batch.max-symbols={})",
                    symbols.size(), cap, maxSymbols);
        }

        AtomicInteger built = new AtomicInteger();
        AtomicInteger written = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        for (int i = 0; i < cap; i++) {
            String symbol = symbols.get(i);
            try {
                Agent1BundleBuilder.Built b = bundleBuilder.build(batchUserId, symbol, batchTimeframe, null, AGENT_VERSION);
                ScanContext ctx = b.scanContext();
                built.incrementAndGet();

                if (skipEmptyContext && isEmptyContext(ctx)) {
                    skipped.incrementAndGet();
                    continue;
                }

                String memoryId = scanContextWriter.write(ctx);
                if (memoryId != null) {
                    written.incrementAndGet();
                } else {
                    failed.incrementAndGet();
                }
            } catch (Exception e) {
                failed.incrementAndGet();
                log.warn("[v2-batch] symbol {} failed: {}", symbol, e.getMessage());
            }

            // Light pacing so we don't slam the memsys MCP — 100ms between writes ≈ 10 writes/sec
            if (written.get() > 0 && written.get() % 10 == 0) {
                try { Thread.sleep(100); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("[v2-batch] interrupted at index {}/{} — exiting early", i, cap);
                    break;
                }
            }
        }

        long elapsedMs = Duration.between(start, Instant.now()).toMillis();
        Summary s = new Summary(trigger, built.get(), written.get(), skipped.get(), failed.get(), elapsedMs, null);
        log.info("[v2-batch] done trigger={} built={} written={} skipped={} failed={} elapsed={}ms",
                trigger, s.built, s.written, s.skipped, s.failed, elapsedMs);
        return s;
    }

    /**
     * "Empty context" means the trader hasn't given Agent 1 anything to interpret on this symbol —
     * no drawings, no annotations, no journal notes, no existing plan groups, no candle patterns
     * worth flagging. Agent 1 will emit only a "no user structure declared" INFO flag in this case,
     * so skipping these symbols saves routine tokens without losing actionable output.
     */
    private boolean isEmptyContext(ScanContext ctx) {
        if (ctx == null) return true;
        boolean noAnnotations = isNullOrEmpty(ctx.getAnnotations());
        boolean noJournal = isNullOrEmpty(ctx.getJournalNotes());
        boolean noDrawings = isNullOrEmpty(ctx.getDrawings());
        boolean noPivotLabels = isNullOrEmpty(ctx.getPivotLabels());
        boolean noExistingGroups = isNullOrEmpty(ctx.getExistingPlanGroups());
        return noAnnotations && noJournal && noDrawings && noPivotLabels && noExistingGroups;
    }

    private static boolean isNullOrEmpty(java.util.Collection<?> c) {
        return c == null || c.isEmpty();
    }

    /** Returned by both scheduled + manual fire. Trigger is "scheduled" or "manual". */
    public record Summary(
            String trigger,
            int built,
            int written,
            int skipped,
            int failed,
            long elapsedMs,
            String error
    ) {}
}
