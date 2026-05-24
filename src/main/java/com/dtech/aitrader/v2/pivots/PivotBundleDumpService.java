package com.dtech.aitrader.v2.pivots;

import com.dtech.aitrader.v2.narrative.bundle.FnoUniverseResolver;
import com.dtech.aitrader.v2.narrative.bundle.NarrativeBundleBuilder;
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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Orchestrates the pivot-bundle dump. For every configured symbol, builds one {@link PivotBundle}
 * covering all 4 TFs and persists via {@link PivotBundleWriter}. Mirrors {@code
 * NarrativeBundleDumpService} in shape — sentinel-based FNO expansion, optional min-daily-bars
 * pre-filter (reuses {@link NarrativeBundleBuilder#countDailyBars}), summary memo at the end.
 *
 * <p>Trigger via REST: {@code POST /api/ai-trader-v2/pivot-bundle/run}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PivotBundleDumpService {

    public static final String FNO_SENTINEL = "__FNO__";

    private final PivotBundleBuilder builder;
    private final PivotBundleWriter writer;
    private final FnoUniverseResolver fnoUniverseResolver;
    private final NarrativeBundleBuilder narrativeBundleBuilder; // for countDailyBars helper
    private final ChartDataService chartDataService;             // for per-TF last-bar lookup

    @Value("${pivot.batch.enabled:false}")
    private boolean enabled;

    @Value("${pivot.batch.user-id:1}")
    private Long batchUserId;

    @Value("${pivot.batch.symbols:" +
            "RELIANCE,HDFCBANK,TCS,INFY,TATASTEEL,SBIN,ITC,ADANIENT,HINDUNILVR,BAJFINANCE," +
            "ICICIBANK,LT,BHARTIARTL,MARUTI,SUNPHARMA}")
    private String symbolsCsv;

    @Value("${pivot.batch.timeframes:Week,Day,OneHour,FifteenMinute}")
    private String tfsCsv;

    @Value("${pivot.batch.tf-labels:weekly,daily,hourly,15min}")
    private String tfLabelsCsv;

    @Value("${pivot.batch.intraday-tfs:OneHour,FifteenMinute}")
    private String intradayTfsCsv;

    @Value("${pivot.batch.min-daily-bars:250}")
    private int minDailyBars;

    @Scheduled(cron = "${pivot.batch.cron:0 45 21 * * MON-FRI}", zone = "Asia/Kolkata")
    public void scheduledRun() {
        if (!enabled) {
            log.debug("[pivot-batch] skipped: pivot.batch.enabled=false");
            return;
        }
        runManual(null, null, null);
    }

    public Summary runManual(String symbolsOverride, String tfsOverride, String dateLabel) {
        Instant start = Instant.now();
        if (dateLabel == null || dateLabel.isBlank()) {
            dateLabel = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
        }

        List<String> rawSymbols = parseCsv(symbolsOverride != null && !symbolsOverride.isBlank()
                ? symbolsOverride : symbolsCsv);
        List<String> defaultTfs = parseCsv(tfsCsv);
        List<String> defaultTfLabels = parseCsv(tfLabelsCsv);
        Map<String, String> tfToLabel = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(defaultTfs.size(), defaultTfLabels.size()); i++) {
            tfToLabel.put(defaultTfs.get(i), defaultTfLabels.get(i));
        }
        tfToLabel.putIfAbsent("Week", "weekly");
        tfToLabel.putIfAbsent("Day", "daily");
        tfToLabel.putIfAbsent("OneHour", "hourly");
        tfToLabel.putIfAbsent("FifteenMinute", "15min");

        List<String> tfs = parseCsv(tfsOverride != null && !tfsOverride.isBlank()
                ? tfsOverride : tfsCsv);
        List<String> tfLabels = tfs.stream()
                .map(tf -> tfToLabel.getOrDefault(tf, tf.toLowerCase()))
                .collect(Collectors.toList());
        java.util.Set<String> intradayTfs = new java.util.LinkedHashSet<>(parseCsv(intradayTfsCsv));

        // FNO sentinel expansion + min-daily-bars pre-filter.
        boolean fnoSweep = rawSymbols.stream().anyMatch(FNO_SENTINEL::equalsIgnoreCase);
        List<String> skippedForHistory = new ArrayList<>();
        List<String> symbols;
        if (fnoSweep) {
            FnoUniverseResolver.Resolved resolved = fnoUniverseResolver.resolve();
            List<String> kept = new ArrayList<>();
            for (String s : resolved.symbols()) {
                if (narrativeBundleBuilder.countDailyBars(s) < minDailyBars) {
                    skippedForHistory.add(s);
                } else {
                    kept.add(s);
                }
            }
            symbols = kept;
            List<String> manual = rawSymbols.stream()
                    .filter(s -> !FNO_SENTINEL.equalsIgnoreCase(s))
                    .collect(Collectors.toList());
            if (!manual.isEmpty()) {
                manual.removeAll(symbols);
                symbols = new ArrayList<>(symbols);
                symbols.addAll(manual);
            }
            log.info("[pivot-batch] FNO sentinel — universe={} kept={} skipped={}",
                    resolved.symbols().size(), kept.size(), skippedForHistory.size());
        } else {
            symbols = rawSymbols;
        }

        log.info("[pivot-batch] start userId={} symbols={} tfs={} date={}",
                batchUserId, symbols.size(), tfs, dateLabel);

        AtomicInteger built = new AtomicInteger();
        AtomicInteger written = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<UuidRow> uuids = new ArrayList<>();

        for (String symbol : symbols) {
            try {
                // Use min(intraday) as the cutoff per fix-3 — first pass to discover it.
                String cutoff = computeCutoff(symbol, tfs, intradayTfs);
                PivotBundle bundle = builder.build(batchUserId, symbol, tfs, tfLabels, cutoff, dateLabel);
                if (bundle == null) {
                    skipped.incrementAndGet();
                    continue;
                }
                built.incrementAndGet();
                String id = writer.write(bundle);
                if (id != null) {
                    written.incrementAndGet();
                    int totalPivots = bundle.getTimeframes().values().stream()
                            .mapToInt(t -> t.getPivots() == null ? 0 : t.getPivots().size()).sum();
                    uuids.add(new UuidRow(symbol, id, bundle.getAsOfDate(),
                            bundle.getTimeframes().size(), totalPivots));
                } else {
                    failed.incrementAndGet();
                }
                if (written.get() > 0 && written.get() % 10 == 0) {
                    try { Thread.sleep(100); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("[pivot-batch] {} failed: {}", symbol, e.getMessage());
                failed.incrementAndGet();
            }
        }

        String summaryBody = renderSummary(uuids, dateLabel, built.get(), written.get(),
                skipped.get() + skippedForHistory.size(), failed.get());
        List<String> summaryExtraTags = fnoSweep ? List.of("fno-full") : null;
        String summaryId = writer.writeSummary(batchUserId, dateLabel, summaryBody, summaryExtraTags);

        long elapsedMs = Duration.between(start, Instant.now()).toMillis();
        Summary s = new Summary("manual", symbols.size(), built.get(), written.get(),
                skipped.get() + skippedForHistory.size(), failed.get(), elapsedMs, null,
                uuids, summaryId);
        log.info("[pivot-batch] done symbols={} built={} written={} skipped={} failed={} elapsed={}ms summary={}",
                symbols.size(), built.get(), written.get(),
                skipped.get() + skippedForHistory.size(), failed.get(), elapsedMs, summaryId);
        return s;
    }

    /**
     * Compute the forward cutoff for this symbol = {@code min(intraday-last-bar dates)}.
     * Per owner-fix-3 (b5ffa13f): if one intraday TF has older data than another, floor ALL TFs
     * to the strictest common date so cross-TF alignment is honest. Returns {@code null} if no
     * intraday TF has any data — Builder will then use each TF's raw last bar.
     */
    private String computeCutoff(String symbol, List<String> tfs, java.util.Set<String> intradayTfs) {
        String floor = null;
        for (String tf : tfs) {
            if (!intradayTfs.contains(tf)) continue;
            try {
                List<OhlcBarDTO> bars = chartDataService.getBars(symbol, tf, null, null, false);
                if (bars == null || bars.isEmpty()) continue;
                String last = LocalDate.ofInstant(
                        Instant.ofEpochSecond(bars.get(bars.size() - 1).getTime()),
                        ZoneId.of("Asia/Kolkata")).toString();
                if (floor == null || last.compareTo(floor) < 0) {
                    floor = last; // MIN — strictest common date
                }
            } catch (Exception e) {
                log.warn("[pivot-batch] {} {} cutoff probe failed: {}", symbol, tf, e.getMessage());
            }
        }
        return floor;
    }

    private static String renderSummary(List<UuidRow> rows, String dateLabel,
                                         int built, int written, int skipped, int failed) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Pivot bundle dump — date=").append(dateLabel).append("\n\n");
        sb.append("Counters: built=").append(built)
                .append(" written=").append(written)
                .append(" skipped=").append(skipped)
                .append(" failed=").append(failed).append("\n\n");
        sb.append("## Per-symbol UUIDs (symbol | uuid | asof | tfs | total_pivots)\n");
        rows.stream().sorted(Comparator.comparing(UuidRow::symbol))
                .forEach(r -> sb.append(r.symbol()).append(" | ")
                        .append(r.uuid()).append(" | ")
                        .append(r.asOfDate()).append(" | ")
                        .append(r.tfCount()).append(" | ")
                        .append(r.totalPivots()).append("\n"));
        sb.append("\nTags: ai-trader-v2 pivot-bundle pivot-bundle-summary date-")
                .append(dateLabel).append("\n");
        return sb.toString();
    }

    private static List<String> parseCsv(String csv) {
        if (csv == null) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    public record UuidRow(String symbol, String uuid, String asOfDate,
                          int tfCount, int totalPivots) {}

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
            String summaryMemoryId
    ) {}
}
