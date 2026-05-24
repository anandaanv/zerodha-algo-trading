package com.dtech.aitrader.v2.narrative.bundle;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Orchestrates the MTF narrative-bundle dump: for every configured symbol and every configured
 * timeframe, build a {@link NarrativeBundle} via {@link NarrativeBundleBuilder} and persist
 * via {@link NarrativeBundleWriter}. Posts a single summary memory at the end (owner b3ff4ca0
 * deliverable).
 *
 * <p>Mirrors {@link com.dtech.aitrader.v2.batch.NightlyBundleDumpService} in shape — same
 * scheduled + manual entry points, same Summary record, same property-driven config. Runs the
 * narrative bundle SEPARATELY from the scan-context bundle (per user: "another thing to upload
 * in separate bundle, in the same pipeline").
 *
 * <p>Config (overridable in application.properties):
 * <ul>
 *   <li>{@code narrative.batch.enabled} — master switch (default false)</li>
 *   <li>{@code narrative.batch.cron} — Spring cron, IST (default "0 30 21 * * MON-FRI")</li>
 *   <li>{@code narrative.batch.user-id} — memsys tenant owner (default 1)</li>
 *   <li>{@code narrative.batch.symbols} — comma-separated symbol list (default = owner's 15 stocks)</li>
 *   <li>{@code narrative.batch.timeframes} — comma-separated TF list (default Week,Day,OneHour,FifteenMinute)</li>
 *   <li>{@code narrative.batch.tf-labels} — public labels matching TFs (default weekly,daily,hourly,15min)</li>
 *   <li>{@code narrative.batch.intraday-tfs} — TFs whose last-bar date drives the forward cutoff
 *       (default Day,OneHour,FifteenMinute — weekly leaks past these so it gets trimmed)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NarrativeBundleDumpService {

    /** Sentinel in the symbols list that expands to the full NSE F&O underlying universe. */
    public static final String FNO_SENTINEL = "__FNO__";

    private final NarrativeBundleBuilder builder;
    private final NarrativeBundleWriter writer;
    private final FnoUniverseResolver fnoUniverseResolver;

    @Value("${narrative.batch.enabled:false}")
    private boolean enabled;

    @Value("${narrative.batch.user-id:1}")
    private Long batchUserId;

    @Value("${narrative.batch.symbols:" +
            "RELIANCE,HDFCBANK,TCS,INFY,TATASTEEL,SBIN,ITC,ADANIENT,HINDUNILVR,BAJFINANCE," +
            "ICICIBANK,LT,BHARTIARTL,MARUTI,SUNPHARMA}")
    private String symbolsCsv;

    @Value("${narrative.batch.timeframes:Week,Day,OneHour,FifteenMinute}")
    private String tfsCsv;

    @Value("${narrative.batch.tf-labels:weekly,daily,hourly,15min}")
    private String tfLabelsCsv;

    /**
     * TFs whose last-bar date jointly drive the forward cutoff. Day was EXCLUDED per owner
     * validation 848fe4b5: keeping Day in this set let the cutoff include Day's fresher bar
     * (e.g. 2026-05-19) and let Daily run a bar longer than hourly/15min (2026-05-18). The
     * fix: compute cutoff from truly-intraday TFs (hourly + 15min) only; trim Day AND Weekly
     * to {@code ≤ cutoff}.
     */
    @Value("${narrative.batch.intraday-tfs:OneHour,FifteenMinute}")
    private String intradayTfsCsv;

    /** Skip symbols with fewer than this many daily bars (owner widen-to-FNO: 250 default). */
    @Value("${narrative.batch.min-daily-bars:250}")
    private int minDailyBars;

    /** Scheduled fire — 21:30 IST weekdays by default. Guarded by {@code narrative.batch.enabled}. */
    @Scheduled(cron = "${narrative.batch.cron:0 30 21 * * MON-FRI}", zone = "Asia/Kolkata")
    public void scheduledRun() {
        if (!enabled) {
            log.debug("[narrative-batch] skipped: narrative.batch.enabled=false");
            return;
        }
        runManual(null, null);
    }

    /**
     * Manual fire. {@code symbolsOverride}/{@code tfsOverride} are optional comma-separated
     * filters — when supplied, only matching symbols/TFs run. {@code dateLabel} is the value
     * for the {@code date-} tag (defaults to today in IST).
     */
    public Summary runManual(String symbolsOverride, String tfsOverride) {
        return runManual(symbolsOverride, tfsOverride, null);
    }

    public Summary runManual(String symbolsOverride, String tfsOverride, String dateLabel) {
        Instant start = Instant.now();
        if (dateLabel == null || dateLabel.isBlank()) {
            dateLabel = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
        }

        List<String> rawSymbols = parseCsv(symbolsOverride != null && !symbolsOverride.isBlank()
                ? symbolsOverride : symbolsCsv);

        // Expand the __FNO__ sentinel to the resolved NSE F&O underlying list.
        boolean fnoSweep = rawSymbols.stream().anyMatch(FNO_SENTINEL::equalsIgnoreCase);
        String fnoUniverseMemoryId = null;
        List<String> skippedForHistory = new ArrayList<>();
        List<String> symbols;
        if (fnoSweep) {
            FnoUniverseResolver.Resolved resolved = fnoUniverseResolver.resolve();
            List<String> candidates = resolved.symbols();
            log.info("[narrative-batch] FNO sentinel detected — resolved {} candidates, filtering by min-daily-bars={}",
                    candidates.size(), minDailyBars);

            // Pre-filter by daily bar count (owner: skip <250 daily).
            List<String> kept = new ArrayList<>();
            List<String> skipReasons = new ArrayList<>();
            for (String s : candidates) {
                int dailyCount = builder.countDailyBars(s);
                if (dailyCount < minDailyBars) {
                    skippedForHistory.add(s);
                    skipReasons.add(s + " (daily_bars=" + dailyCount + " < " + minDailyBars + ")");
                } else {
                    kept.add(s);
                }
            }
            symbols = kept;
            log.info("[narrative-batch] FNO universe: kept={} skipped={} (min-daily-bars={})",
                    kept.size(), skippedForHistory.size(), minDailyBars);

            // Write the canonical fno-universe memory FIRST (owner-required deliverable).
            fnoUniverseMemoryId = writer.writeFnoUniverse(
                    batchUserId, resolved.asOfDate().toString(),
                    kept, skippedForHistory, String.join(", ", skipReasons));

            // Merge any non-sentinel symbols the caller also supplied.
            List<String> manual = rawSymbols.stream()
                    .filter(s -> !FNO_SENTINEL.equalsIgnoreCase(s))
                    .collect(Collectors.toList());
            if (!manual.isEmpty()) {
                manual.removeAll(symbols);
                symbols = new ArrayList<>(symbols);
                symbols.addAll(manual);
            }
        } else {
            symbols = rawSymbols;
        }
        List<String> defaultTfs = parseCsv(tfsCsv);
        List<String> defaultTfLabels = parseCsv(tfLabelsCsv);
        // Map TF enum → label from the configured defaults (Week→weekly, OneHour→hourly, etc).
        Map<String, String> tfToLabel = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(defaultTfs.size(), defaultTfLabels.size()); i++) {
            tfToLabel.put(defaultTfs.get(i), defaultTfLabels.get(i));
        }
        // Built-in fallbacks so any sensible TF enum still gets a label even if not in props.
        tfToLabel.putIfAbsent("Week", "weekly");
        tfToLabel.putIfAbsent("Day", "daily");
        tfToLabel.putIfAbsent("OneHour", "hourly");
        tfToLabel.putIfAbsent("FifteenMinute", "15min");
        tfToLabel.putIfAbsent("FiveMinute", "5min");
        tfToLabel.putIfAbsent("OneMinute", "1min");

        List<String> tfs = parseCsv(tfsOverride != null && !tfsOverride.isBlank()
                ? tfsOverride : tfsCsv);
        List<String> tfLabels = tfs.stream()
                .map(tf -> tfToLabel.getOrDefault(tf, tf.toLowerCase()))
                .collect(Collectors.toList());
        Set<String> intradayTfs = new java.util.LinkedHashSet<>(parseCsv(intradayTfsCsv));

        log.info("[narrative-batch] start userId={} symbols={} tfs={} date={}",
                batchUserId, symbols.size(), tfs, dateLabel);

        AtomicInteger built = new AtomicInteger();
        AtomicInteger written = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<UuidRow> uuids = new ArrayList<>();

        for (String symbol : symbols) {
            // Step A: for this symbol, build all TFs first to compute the per-symbol forward cutoff.
            //
            // Cutoff = MIN(intraday last-bar dates) per owner b5ffa13f issue-3. If one intraday
            // TF lags another, floor ALL TFs (Day, Week, and any leading intraday) to the
            // strictest common date so cross-TF cutoff alignment is honest. The earlier MAX-based
            // logic only trimmed forward and left a leading intraday TF misaligned.
            Map<String, NarrativeBundle> firstPass = new LinkedHashMap<>();
            String cutoff = null;
            for (int i = 0; i < tfs.size(); i++) {
                String tfEnum = tfs.get(i);
                String tfLabel = tfLabels.get(i);
                try {
                    NarrativeBundle b = builder.build(batchUserId, symbol, tfEnum, tfLabel, null, dateLabel);
                    if (b != null) {
                        firstPass.put(tfEnum, b);
                        built.incrementAndGet();
                        if (intradayTfs.contains(tfEnum) && b.getLastBarDate() != null) {
                            if (cutoff == null || b.getLastBarDate().compareTo(cutoff) < 0) {
                                cutoff = b.getLastBarDate(); // MIN — strictest common
                            }
                        }
                    } else {
                        skipped.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.warn("[narrative-batch] {} {} pass-1 build failed: {}", symbol, tfEnum, e.getMessage());
                    failed.incrementAndGet();
                }
            }

            // Step B: for ANY TF whose last-bar date exceeds the cutoff, rebuild with the cutoff
            // applied (drops the leaking bars). With MIN-based cutoff this can also trim a leading
            // intraday TF, not just Day/Week.
            for (int i = 0; i < tfs.size(); i++) {
                String tfEnum = tfs.get(i);
                String tfLabel = tfLabels.get(i);
                NarrativeBundle b = firstPass.get(tfEnum);
                if (b == null) continue;

                if (cutoff != null
                        && b.getLastBarDate() != null
                        && b.getLastBarDate().compareTo(cutoff) > 0) {
                    log.info("[narrative-batch] {} {} — last bar {} past cutoff {}; rebuilding with cutoff",
                            symbol, tfEnum, b.getLastBarDate(), cutoff);
                    try {
                        NarrativeBundle rebuilt = builder.build(batchUserId, symbol, tfEnum, tfLabel,
                                cutoff, dateLabel);
                        if (rebuilt == null) {
                            log.warn("[narrative-batch] {} {} rebuilt to empty; skipping write",
                                    symbol, tfEnum);
                            skipped.incrementAndGet();
                            continue;
                        }
                        b = rebuilt;
                    } catch (Exception e) {
                        log.warn("[narrative-batch] {} {} rebuild failed: {}", symbol, tfEnum, e.getMessage());
                        failed.incrementAndGet();
                        continue;
                    }
                }

                String id = writer.write(b);
                if (id != null) {
                    written.incrementAndGet();
                    uuids.add(new UuidRow(symbol, tfLabel, id, b.getLastBarDate(), b.getBarCount()));
                } else {
                    failed.incrementAndGet();
                }

                // Light pacing — match scan-context dump (10 writes/sec ceiling)
                if (written.get() > 0 && written.get() % 10 == 0) {
                    try { Thread.sleep(100); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("[narrative-batch] interrupted — exiting early");
                        break;
                    }
                }
            }
        }

        // Step C: write the summary memory.
        String summaryId = null;
        if (!uuids.isEmpty()) {
            String summaryBody = renderSummary(uuids, dateLabel, built.get(), written.get(),
                    skipped.get(), failed.get());
            List<String> extraTags = fnoSweep ? List.of("fno-full") : null;
            summaryId = writer.writeSummary(batchUserId, dateLabel, summaryBody, extraTags);
        }

        long elapsedMs = Duration.between(start, Instant.now()).toMillis();
        Summary s = new Summary("manual", symbols.size(), built.get(), written.get(),
                skipped.get() + skippedForHistory.size(), failed.get(), elapsedMs, null,
                uuids, summaryId, fnoUniverseMemoryId, skippedForHistory);
        log.info("[narrative-batch] done symbols={} built={} written={} skipped={} failed={} elapsed={}ms summary={} fno_universe={}",
                symbols.size(), built.get(), written.get(),
                skipped.get() + skippedForHistory.size(), failed.get(),
                elapsedMs, summaryId, fnoUniverseMemoryId);
        return s;
    }

    private static String renderSummary(List<UuidRow> rows, String dateLabel,
                                         int built, int written, int skipped, int failed) {
        StringBuilder sb = new StringBuilder();
        sb.append("MTF narrative-compact v2 run — date=").append(dateLabel).append("\n\n");
        sb.append("Counters: built=").append(built)
                .append(" written=").append(written)
                .append(" skipped=").append(skipped)
                .append(" failed=").append(failed).append("\n\n");
        sb.append("UUIDs (symbol | tf | uuid | last_bar | bars):\n");
        rows.stream()
                .sorted(Comparator.comparing(UuidRow::symbol).thenComparing(UuidRow::tfLabel))
                .forEach(r -> sb.append(r.symbol()).append(" | ")
                        .append(r.tfLabel()).append(" | ")
                        .append(r.uuid()).append(" | ")
                        .append(r.lastBarDate()).append(" | ")
                        .append(r.barCount()).append("\n"));
        sb.append("\nGenerated by NarrativeBundleDumpService (force_new=true, ")
                .append("cutoff-aligned to max intraday last-bar date per symbol).\n");
        sb.append("Tags: ai-trader-v2 indicator-narrative narrative-compact mtf-runup-v2 ")
                .append("date-").append(dateLabel).append(" for-owner-validation\n");
        return sb.toString();
    }

    private static List<String> parseCsv(String csv) {
        if (csv == null) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /** One row in the summary table. */
    public record UuidRow(String symbol, String tfLabel, String uuid,
                          String lastBarDate, int barCount) {}

    /** Returned by scheduled + manual fire. */
    public record Summary(
            String trigger,
            int symbolCount,
            int built,
            int written,
            int skipped,
            int failed,
            long elapsedMs,
            String error,
            List<UuidRow> uuids,
            String summaryMemoryId,
            String fnoUniverseMemoryId,
            List<String> skippedForHistory
    ) {
        // Convenience constructor for the watchlist path (no FNO universe / no history skips).
        public Summary(String trigger, int symbolCount, int built, int written, int skipped,
                       int failed, long elapsedMs, String error, List<UuidRow> uuids, String summaryMemoryId) {
            this(trigger, symbolCount, built, written, skipped, failed, elapsedMs, error,
                    uuids, summaryMemoryId, null, List.of());
        }
    }
}
