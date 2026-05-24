package com.dtech.aitrader.v2.regime.queue;

import com.dtech.aitrader.v2.memsys.MemsysClient;
import com.dtech.aitrader.v2.memsys.MemsysMemory;
import com.dtech.aitrader.v2.narrative.bundle.FnoUniverseResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Component 1 of the watchlist→regime pipeline.
 *
 * <p>Seeds the F&O scan queue at the start of each night, BEFORE the Haiku worker runs. For every
 * symbol in the resolved NSE F&O universe, writes a memsys "pending" queue memory that the
 * worker will later drain.
 *
 * <p>Idempotent: a search for {@code scan-queue + date-TODAY + symbol-<SYM>} runs first; if a
 * pending marker already exists, the seeder skips that symbol (no double-seed). This makes the
 * seeder safe to fire multiple times per night (operator manually or scheduled).
 *
 * <p>Tag contract per schema {@code a44c035a}:
 * <pre>
 *   tags=[ai-trader-v2, scan-queue, date-YYYY-MM-DD, status-pending, symbol-{SYM}]
 *   TTL=108000s (30h — fresh queue each night)
 *   content="{SYM} queued"
 * </pre>
 *
 * <p>The Haiku worker is responsible for flipping pending→done (adds {@code status-done} tag) and
 * deleting the pending marker; this seeder does NOT manage that side.
 *
 * <p>Config:
 * <ul>
 *   <li>{@code regime.queue.enabled} — master switch (default false)</li>
 *   <li>{@code regime.queue.cron} — Spring cron, IST (default "0 0 18 * * MON-FRI" = 18:00 IST)</li>
 *   <li>{@code regime.queue.user-id} — memsys tenant (default 1)</li>
 *   <li>{@code regime.queue.ttl-seconds} — 30h default (108000)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NightlyQueueSeeder {

    private final FnoUniverseResolver fnoUniverseResolver;
    private final MemsysClient memsys;

    @Value("${regime.queue.enabled:false}")
    private boolean enabled;

    @Value("${regime.queue.user-id:1}")
    private Long batchUserId;

    /** TTL for queue markers — owner spec: 30h. */
    @Value("${regime.queue.ttl-seconds:108000}")
    private long ttlSeconds;

    /** Scheduled fire — 18:00 IST weekdays by default. Guarded by {@code regime.queue.enabled}. */
    @Scheduled(cron = "${regime.queue.cron:0 0 18 * * MON-FRI}", zone = "Asia/Kolkata")
    public void scheduledRun() {
        if (!enabled) {
            log.debug("[queue-seeder] skipped: regime.queue.enabled=false");
            return;
        }
        runManual(null);
    }

    /**
     * Manual fire. {@code dateLabel} is the YYYY-MM-DD value used in the {@code date-} tag and
     * the idempotency search; defaults to today in IST.
     */
    public Summary runManual(String dateLabel) {
        Instant start = Instant.now();
        if (dateLabel == null || dateLabel.isBlank()) {
            dateLabel = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
        }

        FnoUniverseResolver.Resolved resolved = fnoUniverseResolver.resolve();
        List<String> symbols = resolved.symbols();
        log.info("[queue-seeder] start date={} symbols={} ttlSeconds={}",
                dateLabel, symbols.size(), ttlSeconds);

        AtomicInteger seeded = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<String> failedSymbols = new ArrayList<>();
        Instant expiresAt = Instant.now().plus(ttlSeconds, ChronoUnit.SECONDS);

        for (int i = 0; i < symbols.size(); i++) {
            String symbol = symbols.get(i);
            try {
                if (alreadyPending(symbol, dateLabel)) {
                    skipped.incrementAndGet();
                    continue;
                }
                List<String> tags = List.of(
                        "ai-trader-v2",
                        "scan-queue",
                        "date-" + dateLabel,
                        "status-pending",
                        "symbol-" + symbol);
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("symbol", symbol);
                metadata.put("date", dateLabel);
                metadata.put("status", "pending");
                metadata.put("seeded_at", Instant.now().toString());

                memsys.writeMemory(
                        batchUserId,
                        symbol + " queued",
                        "note",
                        tags,
                        metadata,
                        /*parentId*/ null,
                        /*supersedes*/ null,
                        /*forceNew*/ true,
                        /*indexable*/ false,
                        expiresAt);
                seeded.incrementAndGet();
            } catch (Exception e) {
                log.warn("[queue-seeder] {} failed: {}", symbol, e.getMessage());
                failed.incrementAndGet();
                failedSymbols.add(symbol);
            }
            // Light pacing — mirror scan-context dumper (100ms per 10 writes).
            if (seeded.get() > 0 && seeded.get() % 10 == 0) {
                try { Thread.sleep(100); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("[queue-seeder] interrupted at index {}/{}", i, symbols.size());
                    break;
                }
            }
        }

        long elapsedMs = Duration.between(start, Instant.now()).toMillis();
        Summary s = new Summary(dateLabel, symbols.size(), seeded.get(), skipped.get(),
                failed.get(), failedSymbols, elapsedMs);
        log.info("[queue-seeder] done date={} total={} seeded={} skipped={} failed={} elapsed={}ms",
                dateLabel, symbols.size(), seeded.get(), skipped.get(), failed.get(), elapsedMs);
        return s;
    }

    /** Returns true if a pending queue marker for this {@code (symbol, dateLabel)} already exists. */
    private boolean alreadyPending(String symbol, String dateLabel) {
        try {
            List<String> tags = List.of(
                    "scan-queue",
                    "date-" + dateLabel,
                    "status-pending",
                    "symbol-" + symbol);
            List<MemsysMemory> hits = memsys.searchMemories(
                    batchUserId,
                    symbol + " queued",
                    tags,
                    /*type*/ null,
                    /*parentId*/ null,
                    /*since*/ null,
                    /*until*/ null,
                    /*limit*/ 1);
            return hits != null && !hits.isEmpty();
        } catch (Exception e) {
            // Best-effort idempotency — if the search call fails, fall through to re-write.
            log.warn("[queue-seeder] {} idempotency search failed (will write anyway): {}",
                    symbol, e.getMessage());
            return false;
        }
    }

    public record Summary(
            String dateLabel,
            int total,
            int seeded,
            int skipped,
            int failed,
            List<String> failedSymbols,
            long elapsedMs
    ) {}
}
