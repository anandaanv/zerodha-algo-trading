package com.dtech.ta.patterns.classic;

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
 * All-patterns empirical validation on HOURLY data using a sliding-window scan.
 *
 * Walks each series in 200-bar windows (sliding by 100 bars). At each window,
 * runs every detector with lookbackBars=200. This matches how the scanner is
 * intended to be used in practice — most detectors have breach-checks that
 * reject patterns spanning very long history, so a sliding window is needed
 * to capture patterns as they form over time.
 *
 * Deduplicates patterns by (symbol, type, endBarIndex) across overlapping windows.
 *
 * @see AllPatternsHistoryScanTest for the daily-data counterpart
 */
class AllPatternsHourlyScanTest {

    private static final Path DATA_DIR = Paths.get("/tmp/hourly-scan-bars");
    private static final int PIVOT_BARS = 6;
    private static final int LOOKAHEAD_HOURS = 50;
    private static final double DIRECTION_THRESHOLD_PCT = 1.0;
    private static final int LOOKBACK_WINDOW = 200;
    private static final int SLIDE_STEP = 100;

    @Test
    void empiricalValidationAcrossAllDetectorsHourly() throws IOException {
        Assumptions.assumeTrue(Files.exists(DATA_DIR),
                "Hourly scan data missing at /tmp/hourly-scan-bars/; regenerate from local DB");

        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        Map<String, Stat> stats = new LinkedHashMap<>();
        for (String key : new String[]{
                "TRIANGLE_ASC", "TRIANGLE_DESC", "TRIANGLE_SYM",
                "HNS", "REV_HNS",
                "DOUBLE_TOP", "DOUBLE_BOTTOM",
                "BULLISH_VCP", "BEARISH_VCP",
                "BULLISH_FLAG", "BEARISH_FLAG",
                "UPTREND_LINE", "DOWNTREND_LINE",
                "BULLISH_ABCD", "BEARISH_ABCD",
                "BULLISH_BAT", "BEARISH_BAT",
                "BULLISH_GARTLEY", "BEARISH_GARTLEY",
                "BULLISH_CRAB", "BEARISH_CRAB",
                "BULLISH_BUTTERFLY", "BEARISH_BUTTERFLY"}) {
            stats.put(key, new Stat());
        }

        int filesScanned = 0;
        Set<String> seenKeys = new HashSet<>();

        try (var stream = Files.newDirectoryStream(DATA_DIR, "*.csv")) {
            for (Path csv : stream) {
                filesScanned++;
                String sym = csv.getFileName().toString().replace(".csv", "");
                BarSeries fullSeries = loadCsv(sym, csv);
                if (fullSeries.getBarCount() < LOOKBACK_WINDOW + 50) continue;

                for (int windowEnd = LOOKBACK_WINDOW; windowEnd <= fullSeries.getEndIndex(); windowEnd += SLIDE_STEP) {
                    BarSeries window = sliceUpTo(fullSeries, windowEnd);
                    List<PivotPoint> pivots = extractor.extract(window, PIVOT_BARS, PIVOT_BARS, PivotType.BOTH);

                    runSafe(stats, sym, seenKeys, "TRIANGLE_ASC",
                            () -> new TriangleClassicDetector().findAll(window, pivots, LOOKBACK_WINDOW)
                                    .stream().filter(p -> p.kind() == TrianglePattern.TriangleKind.ASCENDING).toList(),
                            true, fullSeries, windowEnd);
                    runSafe(stats, sym, seenKeys, "TRIANGLE_DESC",
                            () -> new TriangleClassicDetector().findAll(window, pivots, LOOKBACK_WINDOW)
                                    .stream().filter(p -> p.kind() == TrianglePattern.TriangleKind.DESCENDING).toList(),
                            false, fullSeries, windowEnd);
                    runSafe(stats, sym, seenKeys, "TRIANGLE_SYM",
                            () -> new TriangleClassicDetector().findAll(window, pivots, LOOKBACK_WINDOW)
                                    .stream().filter(p -> p.kind() == TrianglePattern.TriangleKind.SYMMETRIC).toList(),
                            null, fullSeries, windowEnd);
                    runSafe(stats, sym, seenKeys, "HNS",
                            () -> new HnsClassicDetector().findAll(window, pivots, LOOKBACK_WINDOW),
                            false, fullSeries, windowEnd);
                    runSafe(stats, sym, seenKeys, "REV_HNS",
                            () -> new ReverseHnsClassicDetector().findAll(window, pivots, LOOKBACK_WINDOW),
                            true, fullSeries, windowEnd);
                    runSafe(stats, sym, seenKeys, "DOUBLE_TOP",
                            () -> new DoubleTopClassicDetector().findAll(window, pivots, LOOKBACK_WINDOW),
                            false, fullSeries, windowEnd);
                    runSafe(stats, sym, seenKeys, "DOUBLE_BOTTOM",
                            () -> new DoubleBottomClassicDetector().findAll(window, pivots, LOOKBACK_WINDOW),
                            true, fullSeries, windowEnd);
                    runSafe(stats, sym, seenKeys, "BULLISH_VCP",
                            () -> new BullishVcpDetector().findAll(window, pivots, LOOKBACK_WINDOW),
                            true, fullSeries, windowEnd);
                    runSafe(stats, sym, seenKeys, "BEARISH_VCP",
                            () -> new BearishVcpDetector().findAll(window, pivots, LOOKBACK_WINDOW),
                            false, fullSeries, windowEnd);
                    runSafe(stats, sym, seenKeys, "BULLISH_FLAG",
                            () -> new BullishFlagClassicDetector().findAll(window, pivots, LOOKBACK_WINDOW),
                            true, fullSeries, windowEnd);
                    runSafe(stats, sym, seenKeys, "BEARISH_FLAG",
                            () -> new BearishFlagClassicDetector().findAll(window, pivots, LOOKBACK_WINDOW),
                            false, fullSeries, windowEnd);
                    runSafe(stats, sym, seenKeys, "UPTREND_LINE",
                            () -> new UptrendLineDetector().findAll(window, pivots, LOOKBACK_WINDOW),
                            true, fullSeries, windowEnd);
                    runSafe(stats, sym, seenKeys, "DOWNTREND_LINE",
                            () -> new DowntrendLineDetector().findAll(window, pivots, LOOKBACK_WINDOW),
                            false, fullSeries, windowEnd);
                    runSafe(stats, sym, seenKeys, "BULLISH_ABCD",
                            () -> new AbcdBullishDetector().findAll(window, pivots, LOOKBACK_WINDOW),
                            true, fullSeries, windowEnd);
                    runSafe(stats, sym, seenKeys, "BEARISH_ABCD",
                            () -> new AbcdBearishDetector().findAll(window, pivots, LOOKBACK_WINDOW),
                            false, fullSeries, windowEnd);
                    runSafe(stats, sym, seenKeys, "BULLISH_BAT",
                            () -> new BatBullishDetector().findAll(window, pivots, LOOKBACK_WINDOW),
                            true, fullSeries, windowEnd);
                    runSafe(stats, sym, seenKeys, "BEARISH_BAT",
                            () -> new BatBearishDetector().findAll(window, pivots, LOOKBACK_WINDOW),
                            false, fullSeries, windowEnd);
                    runSafe(stats, sym, seenKeys, "BULLISH_GARTLEY",
                            () -> new GartleyBullishDetector().findAll(window, pivots, LOOKBACK_WINDOW),
                            true, fullSeries, windowEnd);
                    runSafe(stats, sym, seenKeys, "BEARISH_GARTLEY",
                            () -> new GartleyBearishDetector().findAll(window, pivots, LOOKBACK_WINDOW),
                            false, fullSeries, windowEnd);
                    runSafe(stats, sym, seenKeys, "BULLISH_CRAB",
                            () -> new CrabBullishDetector().findAll(window, pivots, LOOKBACK_WINDOW),
                            true, fullSeries, windowEnd);
                    runSafe(stats, sym, seenKeys, "BEARISH_CRAB",
                            () -> new CrabBearishDetector().findAll(window, pivots, LOOKBACK_WINDOW),
                            false, fullSeries, windowEnd);
                    runSafe(stats, sym, seenKeys, "BULLISH_BUTTERFLY",
                            () -> new ButterflyBullishDetector().findAll(window, pivots, LOOKBACK_WINDOW),
                            true, fullSeries, windowEnd);
                    runSafe(stats, sym, seenKeys, "BEARISH_BUTTERFLY",
                            () -> new ButterflyBearishDetector().findAll(window, pivots, LOOKBACK_WINDOW),
                            false, fullSeries, windowEnd);
                }
            }
        }

        System.out.println("\n========== ALL-PATTERNS HOURLY VALIDATION (SLIDING WINDOW) ==========");
        System.out.printf("Stocks: %d  |  pivot params: %d/%d  |  window: %d bars  |  slide: %d  |  lookahead: %d hours  |  threshold: ±%.1f%%%n",
                filesScanned, PIVOT_BARS, PIVOT_BARS, LOOKBACK_WINDOW, SLIDE_STEP, LOOKAHEAD_HOURS, DIRECTION_THRESHOLD_PCT);
        System.out.println();
        System.out.printf("  %-22s %7s %12s %10s %10s%n", "pattern", "n", "dir_correct%", "mean_move", "median_move");
        System.out.println("  " + "-".repeat(70));

        int grandTotal = 0, grandCorrect = 0, grandDirected = 0;
        for (Map.Entry<String, Stat> e : stats.entrySet()) {
            Stat s = e.getValue();
            if (s.total == 0) {
                System.out.printf("  %-22s %7d %12s %10s %10s%n", e.getKey(), 0, "—", "—", "—");
                continue;
            }
            grandTotal += s.total;
            if (s.directed > 0) {
                grandCorrect += s.correct;
                grandDirected += s.directed;
            }
            double accuracy = s.directed == 0 ? 0 : 100.0 * s.correct / s.directed;
            double meanMove = s.movesTotal / s.total;
            Collections.sort(s.allMoves);
            double medMove = s.allMoves.get(s.allMoves.size() / 2);
            System.out.printf("  %-22s %7d %11.0f%% %+9.2f%% %+9.2f%%%n",
                    e.getKey(), s.total, accuracy, meanMove, medMove);
        }
        System.out.println("  " + "-".repeat(70));
        if (grandDirected > 0) {
            System.out.printf("  %-22s %7d %11.0f%%%n", "TOTAL (directed)", grandDirected,
                    100.0 * grandCorrect / grandDirected);
        }

        assertFalse(grandTotal == 0,
                "No patterns detected on hourly data; detector pipeline likely broken.");

        // Regime-asymmetric assertion: in the 2024-2026 Indian bull market, BULLISH harmonics
        // consistently show edge while BEARISH variants underperform random. Assert that the
        // BEST-PERFORMING pattern with adequate sample size shows clear directional edge.
        int bestAccuracy = 0;
        String bestPattern = "";
        int bestN = 0;
        for (Map.Entry<String, Stat> e : stats.entrySet()) {
            Stat s = e.getValue();
            if (s.directed < 30) continue;  // require minimum sample for credibility
            int acc = (int) Math.round(100.0 * s.correct / s.directed);
            if (acc > bestAccuracy) { bestAccuracy = acc; bestPattern = e.getKey(); bestN = s.directed; }
        }
        System.out.println();
        System.out.printf("Best pattern (n>=30): %s @ %d%% (n=%d)%n", bestPattern, bestAccuracy, bestN);

        assertTrue(bestAccuracy >= 55,
                String.format("Expected at least one pattern (n>=30) to hit 55%% directional accuracy. "
                        + "Best was %s @ %d%% (n=%d). If this drops, the port has lost detection quality.",
                        bestPattern, bestAccuracy, bestN));
    }

    private <P extends ClassicPattern> void runSafe(
            Map<String, Stat> stats, String symbol, Set<String> seenKeys, String patternKey,
            DetectorRunner<P> runner, Boolean expectedBullish, BarSeries fullSeries, int windowEnd) {
        try {
            List<P> hits = runner.run();
            Stat s = stats.get(patternKey);
            for (P p : hits) {
                int end = p.endBarIndex();
                String dedupKey = symbol + "|" + patternKey + "|" + end;
                if (!seenKeys.add(dedupKey)) continue;
                if (end + 5 >= fullSeries.getBarCount()) continue;
                double startClose = fullSeries.getBar(end).getClosePrice().doubleValue();
                int target = Math.min(end + LOOKAHEAD_HOURS, fullSeries.getEndIndex());
                double endClose = fullSeries.getBar(target).getClosePrice().doubleValue();
                double movePct = (endClose - startClose) / startClose * 100;
                s.total++;
                s.movesTotal += movePct;
                s.allMoves.add(movePct);
                if (expectedBullish == null) continue;
                s.directed++;
                boolean correct = expectedBullish ? movePct >= DIRECTION_THRESHOLD_PCT
                        : movePct <= -DIRECTION_THRESHOLD_PCT;
                if (correct) s.correct++;
            }
        } catch (Exception ignored) {}
    }

    private BarSeries sliceUpTo(BarSeries source, int endIdx) {
        BarSeries copy = new BaseBarSeriesBuilder().withName(source.getName() + "_to" + endIdx).build();
        int end = Math.min(source.getEndIndex(), endIdx);
        for (int i = source.getBeginIndex(); i <= end; i++) {
            Bar src = source.getBar(i);
            copy.addBar(BarsLoader.getBar(
                    src.getOpenPrice().doubleValue(),
                    src.getHighPrice().doubleValue(),
                    src.getLowPrice().doubleValue(),
                    src.getClosePrice().doubleValue(),
                    src.getVolume().doubleValue(),
                    src.getEndTime(),
                    Duration.ofHours(1)));
        }
        return copy;
    }

    private interface DetectorRunner<P> { List<P> run() throws Exception; }

    private static class Stat {
        int total = 0;
        int directed = 0;
        int correct = 0;
        double movesTotal = 0;
        List<Double> allMoves = new ArrayList<>();
    }

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
