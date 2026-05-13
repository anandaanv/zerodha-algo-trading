package com.dtech.ta.patterns.classic;

import com.dtech.chartpattern.zigzag.ZigZagParams;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.kitecon.simulation.CandidatePivotZigZag;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compare classic pattern detection results when fed by:
 *   (A) Williams-style {@link ClassicPivotExtractor} (barsLeft=barsRight=6)
 *   (B) {@link CandidatePivotZigZag} (CPZZ) confirmed pivots
 *
 * Goal: quantify how many of the same patterns fire with each pivot source, and
 * how much overlap exists. This informs whether the classic detectors should
 * migrate to CPZZ for live trading (CPZZ is incremental and confirms pivots
 * via reversal candles rather than waiting for barsRight bars of confirmation).
 *
 * Reports per pattern type:
 *   - Williams count, CPZZ count
 *   - Overlap (same pattern type with endBar within ±5 bars on the SAME stock)
 *   - Williams-only / CPZZ-only counts
 *
 * @see AllPatternsHourlyScanTest baseline (Williams only)
 */
class CpzzVsWilliamsPivotComparisonTest {

    private static final Path DATA_DIR = Paths.get("/tmp/hourly-scan-bars");
    private static final int WILLIAMS_BARS = 6;
    private static final int LOOKBACK_WINDOW = 200;
    private static final int SLIDE_STEP = 100;
    private static final int OVERLAP_TOLERANCE_BARS = 5;

    @Test
    void compareCpzzVsWilliamsForClassicPatterns() throws IOException {
        Assumptions.assumeTrue(Files.exists(DATA_DIR),
                "Hourly scan data missing at " + DATA_DIR);

        // Collect detections per (pivot-source, pattern-type, symbol)
        Map<String, List<Detection>> williamsByKey = new HashMap<>();
        Map<String, List<Detection>> cpzzByKey = new HashMap<>();

        int filesProcessed = 0;
        try (var stream = Files.newDirectoryStream(DATA_DIR, "*.csv")) {
            for (Path csv : stream) {
                String sym = csv.getFileName().toString().replace(".csv", "");
                BarSeries series = loadCsv(sym, csv);
                if (series.getBarCount() < LOOKBACK_WINDOW + 50) continue;
                filesProcessed++;

                // (A) Williams pivots — incremental within each sliding window
                List<PivotPoint> williamsPivots = new ClassicPivotExtractor()
                        .extract(series, WILLIAMS_BARS, WILLIAMS_BARS, PivotType.BOTH);

                // (B) CPZZ pivots — process each bar incrementally, get confirmed pivots
                List<PivotPoint> cpzzPivots = runCpzz(series);

                // Note pivot counts for each
                if (filesProcessed <= 5) {
                    System.out.printf("  %s: bars=%d, williams pivots=%d, cpzz pivots=%d%n",
                            sym, series.getBarCount(), williamsPivots.size(), cpzzPivots.size());
                }

                // Run a curated subset of detectors with each pivot source.
                // Focus on the ones that work well per earlier scans.
                runDetectors(sym, series, williamsPivots, williamsByKey);
                runDetectors(sym, series, cpzzPivots, cpzzByKey);
            }
        }

        System.out.printf("%nFiles processed: %d%n%n", filesProcessed);

        // Compare per pattern type
        System.out.println("========== CPZZ vs WILLIAMS PIVOT COMPARISON ==========");
        System.out.printf("  %-22s %12s %10s %10s %10s %10s%n",
                "pattern", "williams_n", "cpzz_n", "overlap", "wOnly", "cOnly");
        System.out.println("  " + "-".repeat(80));

        Set<String> allTypes = new LinkedHashSet<>();
        allTypes.addAll(williamsByKey.keySet());
        allTypes.addAll(cpzzByKey.keySet());

        int totalWilliams = 0, totalCpzz = 0, totalOverlap = 0;
        for (String type : allTypes) {
            List<Detection> w = williamsByKey.getOrDefault(type, List.of());
            List<Detection> c = cpzzByKey.getOrDefault(type, List.of());
            int overlap = countOverlap(w, c);
            int wOnly = w.size() - overlap;
            int cOnly = c.size() - overlap;
            System.out.printf("  %-22s %12d %10d %10d %10d %10d%n",
                    type, w.size(), c.size(), overlap, wOnly, cOnly);
            totalWilliams += w.size();
            totalCpzz += c.size();
            totalOverlap += overlap;
        }
        System.out.println("  " + "-".repeat(80));
        System.out.printf("  %-22s %12d %10d %10d%n",
                "TOTAL", totalWilliams, totalCpzz, totalOverlap);

        if (totalWilliams > 0 || totalCpzz > 0) {
            double overlapRate = 100.0 * totalOverlap / Math.max(1, Math.min(totalWilliams, totalCpzz));
            System.out.printf("%nOverlap rate: %.0f%% (overlap / min(w, c))%n", overlapRate);
        }

        assertFalse(totalWilliams == 0,
                "Williams pivot source produced zero detections — pipeline broken");
        assertFalse(totalCpzz == 0,
                "CPZZ pivot source produced zero detections — CPZZ adapter or detector broken");
    }

    /** Run CPZZ over the entire series incrementally, return confirmed pivots as PivotPoints. */
    private List<PivotPoint> runCpzz(BarSeries series) {
        ZigZagParams params = ZigZagParams.ofDefaults(
                14, 1.0, 0.005, 1.0, 1, false, 1.0, 14,
                ZigZagParams.Mode.BACKTEST);
        CandidatePivotZigZag cpzz = new CandidatePivotZigZag(params);
        for (int i = 0; i < series.getBarCount(); i++) {
            cpzz.processBar(series, i);
        }
        List<PivotPoint> result = new ArrayList<>();
        for (ZigZagPoint zp : cpzz.getConfirmedPivots()) {
            int barIndex = zp.getBarIndex();
            // ZigZagPoint barIndex may be a derived sequence-based index — find the matching
            // bar by timestamp to ensure indices align with the original series.
            int actualIndex = findBarByTimestamp(series, zp.getTimestamp());
            if (actualIndex < 0) continue;
            PivotType type = zp.isHigh() ? PivotType.HIGH : PivotType.LOW;
            result.add(new PivotPoint(actualIndex, zp.getTimestamp(), zp.getValue(), type));
        }
        // Pivots from CPZZ may not be in bar-index order; sort defensively
        result.sort(Comparator.comparingInt(PivotPoint::barIndex));
        return result;
    }

    private int findBarByTimestamp(BarSeries series, Instant ts) {
        // Binary search by end time
        int lo = series.getBeginIndex(), hi = series.getEndIndex();
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Instant midTs = series.getBar(mid).getEndTime();
            int cmp = midTs.compareTo(ts);
            if (cmp == 0) return mid;
            if (cmp < 0) lo = mid + 1;
            else hi = mid - 1;
        }
        return -1;
    }

    /** Run the high-edge detectors and capture their detections under the symbol key. */
    private void runDetectors(String sym, BarSeries series, List<PivotPoint> pivots,
                              Map<String, List<Detection>> accumulator) {
        if (pivots.size() < 4) return;
        captureSafe(sym, "BULLISH_ABCD", series, accumulator,
                () -> new AbcdBullishDetector().findAll(series, pivots, series.getBarCount()));
        captureSafe(sym, "BEARISH_ABCD", series, accumulator,
                () -> new AbcdBearishDetector().findAll(series, pivots, series.getBarCount()));
        captureSafe(sym, "BULLISH_BAT", series, accumulator,
                () -> new BatBullishDetector().findAll(series, pivots, series.getBarCount()));
        captureSafe(sym, "BULLISH_CRAB", series, accumulator,
                () -> new CrabBullishDetector().findAll(series, pivots, series.getBarCount()));
        captureSafe(sym, "REV_HNS", series, accumulator,
                () -> new ReverseHnsClassicDetector().findAll(series, pivots, series.getBarCount()));
        captureSafe(sym, "HNS", series, accumulator,
                () -> new HnsClassicDetector().findAll(series, pivots, series.getBarCount()));
        captureSafe(sym, "DOUBLE_TOP", series, accumulator,
                () -> new DoubleTopClassicDetector().findAll(series, pivots, series.getBarCount()));
        captureSafe(sym, "DOUBLE_BOTTOM", series, accumulator,
                () -> new DoubleBottomClassicDetector().findAll(series, pivots, series.getBarCount()));
        captureSafe(sym, "TRIANGLE", series, accumulator,
                () -> new TriangleClassicDetector().findAll(series, pivots, series.getBarCount()));
        captureSafe(sym, "BULLISH_VCP", series, accumulator,
                () -> new BullishVcpDetector().findAll(series, pivots, series.getBarCount()));
        captureSafe(sym, "BEARISH_VCP", series, accumulator,
                () -> new BearishVcpDetector().findAll(series, pivots, series.getBarCount()));
    }

    private <P extends ClassicPattern> void captureSafe(
            String sym, String typeKey, BarSeries series,
            Map<String, List<Detection>> acc, DetectorRunner<P> runner) {
        try {
            for (P p : runner.run()) {
                acc.computeIfAbsent(typeKey, k -> new ArrayList<>())
                        .add(new Detection(sym, typeKey, p.endBarIndex()));
            }
        } catch (Exception ignored) {}
    }

    private int countOverlap(List<Detection> a, List<Detection> b) {
        // Two detections overlap when same symbol + endBar within tolerance
        Map<String, List<Integer>> bIdx = new HashMap<>();
        for (Detection d : b) {
            bIdx.computeIfAbsent(d.symbol, k -> new ArrayList<>()).add(d.endBar);
        }
        int overlap = 0;
        Set<String> matched = new HashSet<>();
        for (Detection da : a) {
            List<Integer> candidates = bIdx.getOrDefault(da.symbol, List.of());
            for (Integer cb : candidates) {
                String key = da.symbol + "|" + cb;
                if (matched.contains(key)) continue;
                if (Math.abs(da.endBar - cb) <= OVERLAP_TOLERANCE_BARS) {
                    overlap++;
                    matched.add(key);
                    break;
                }
            }
        }
        return overlap;
    }

    private interface DetectorRunner<P> { List<P> run() throws Exception; }
    private record Detection(String symbol, String type, int endBar) {}

    private BarSeries loadCsv(String sym, Path csv) throws IOException {
        BarSeries series = new BaseBarSeriesBuilder().withName(sym).build();
        List<String> lines = Files.readAllLines(csv);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split(",");
            if (parts.length < 6) continue;
            String ts = parts[0]; if (!ts.endsWith("Z")) ts += "Z";
            series.addBar(BarsLoader.getBar(
                    Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]), Double.parseDouble(parts[4]),
                    Double.parseDouble(parts[5]), Instant.parse(ts), Duration.ofHours(1)));
        }
        return series;
    }
}
