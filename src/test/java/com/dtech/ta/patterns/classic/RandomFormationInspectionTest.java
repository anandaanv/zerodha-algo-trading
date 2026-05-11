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
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inspect random pattern detections + test on volatile stocks.
 *
 * Two parts:
 *  1. Pick random detections across the hourly cohort and dump full diagnostic
 *     info (pivot points with dates + prices, surrounding bars, computed metrics)
 *     so a human can sanity-check that the geometry is genuinely a pattern.
 *  2. Run all detectors on a separate cohort of 26 high-volatility stocks
 *     (Adani group, YesBank, Paytm, Suzlon, IRFC, etc.) and report per-pattern
 *     directional accuracy. Volatile stocks stress the detectors' avgBarLength
 *     normalization.
 *
 * Test asserts: random sample is dumped (no automated correctness check —
 * by design, for human review), and volatile-stock validation produces
 * non-zero detections with at least one pattern showing real edge.
 *
 * @see AllPatternsHourlyScanTest for the main Nifty-50 hourly scan
 */
class RandomFormationInspectionTest {

    private static final Path NIFTY_DIR = Paths.get("/tmp/hourly-scan-bars");
    private static final Path VOLATILE_DIR = Paths.get("/tmp/volatile-hourly");
    private static final int PIVOT_BARS = 6;
    private static final int LOOKBACK_WINDOW = 200;
    private static final int SLIDE_STEP = 100;
    private static final int LOOKAHEAD = 50;
    private static final int RANDOM_SAMPLE_SIZE = 12;
    private static final long RANDOM_SEED = 42L;

    @Test
    void inspectRandomFormations() throws IOException {
        Assumptions.assumeTrue(Files.exists(NIFTY_DIR),
                "Hourly scan data missing at " + NIFTY_DIR);

        List<Detection> all = collectAllDetections(NIFTY_DIR);
        assertFalse(all.isEmpty(), "Expected detections from Nifty hourly cohort");

        Random rng = new Random(RANDOM_SEED);
        Collections.shuffle(all, rng);
        List<Detection> sample = all.subList(0, Math.min(RANDOM_SAMPLE_SIZE, all.size()));

        System.out.println("\n========== RANDOM FORMATION INSPECTION ==========");
        System.out.printf("Sampled %d random detections from %d total. Inspect manually for geometric validity.%n",
                sample.size(), all.size());
        System.out.println();

        for (int i = 0; i < sample.size(); i++) {
            Detection d = sample.get(i);
            System.out.printf("--- [%d/%d] %s %s @ %s (endBar=%d) ---%n",
                    i + 1, sample.size(), d.symbol, d.patternType, d.endDate, d.endBarIndex);
            System.out.println("Pivots (chronological):");
            for (int pi = 0; pi < d.pivots.size(); pi++) {
                PivotPoint p = d.pivots.get(pi);
                String label = pivotLabel(d.patternType, pi);
                System.out.printf("  %s bar=%d  date=%s  %-4s  price=%.2f%n",
                        label, p.barIndex(),
                        p.time().atZone(ZoneId.of("Asia/Kolkata")).toLocalDate(),
                        p.type(), p.price());
            }
            System.out.printf("  avgBarLength=%.3f  fwd_50h_close_move=%+.2f%%%n",
                    d.avgBarLength, d.fwdMovePct);
            printGeometryDiagnostics(d);
            System.out.println();
        }
    }

    @Test
    void scanVolatileStocks() throws IOException {
        Assumptions.assumeTrue(Files.exists(VOLATILE_DIR),
                "Volatile-stock data missing at " + VOLATILE_DIR);

        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        Map<String, Stat> stats = new LinkedHashMap<>();
        for (String key : ALL_PATTERN_KEYS) stats.put(key, new Stat());

        int files = 0;
        Set<String> seen = new HashSet<>();
        try (var stream = Files.newDirectoryStream(VOLATILE_DIR, "*.csv")) {
            for (Path csv : stream) {
                files++;
                String sym = csv.getFileName().toString().replace(".csv", "");
                BarSeries full = loadCsv(sym, csv);
                if (full.getBarCount() < LOOKBACK_WINDOW + 50) continue;

                for (int we = LOOKBACK_WINDOW; we <= full.getEndIndex(); we += SLIDE_STEP) {
                    BarSeries w = sliceUpTo(full, we);
                    List<PivotPoint> pivots = extractor.extract(w, PIVOT_BARS, PIVOT_BARS, PivotType.BOTH);
                    runAllDetectors(stats, sym, seen, full, w, pivots);
                }
            }
        }

        System.out.println("\n========== VOLATILE STOCKS HOURLY VALIDATION ==========");
        System.out.printf("Stocks: %d (Adani, YesBank, Paytm, Suzlon, IRFC, etc.)  |  window: %d  |  slide: %d%n",
                files, LOOKBACK_WINDOW, SLIDE_STEP);
        System.out.println();
        printStatsTable(stats);

        int gTotal = 0, gCorrect = 0, gDirected = 0;
        int bestAcc = 0;
        String bestPat = "";
        int bestN = 0;
        for (var e : stats.entrySet()) {
            Stat s = e.getValue();
            if (s.total == 0) continue;
            gTotal += s.total;
            if (s.directed > 0) {
                gCorrect += s.correct;
                gDirected += s.directed;
                if (s.directed >= 30) {
                    int acc = (int) Math.round(100.0 * s.correct / s.directed);
                    if (acc > bestAcc) { bestAcc = acc; bestPat = e.getKey(); bestN = s.directed; }
                }
            }
        }
        System.out.printf("%nBest pattern (n>=30) on volatile cohort: %s @ %d%% (n=%d)%n",
                bestPat, bestAcc, bestN);

        assertFalse(gTotal == 0,
                "Volatile stocks produced zero pattern detections; detector may be too strict for high-vol regimes");
        assertTrue(bestAcc >= 55,
                String.format("Volatile-stock best pattern only %d%% — port may need vol-adaptive thresholds. "
                        + "Best: %s n=%d", bestAcc, bestPat, bestN));
    }

    // --- Pattern label helpers ---
    private String pivotLabel(String patternType, int idx) {
        return switch (patternType) {
            case "BULLISH_ABCD", "BEARISH_ABCD" -> new String[]{"A", "B", "C", "D"}[Math.min(idx, 3)];
            case "BULLISH_BAT", "BEARISH_BAT",
                 "BULLISH_GARTLEY", "BEARISH_GARTLEY",
                 "BULLISH_CRAB", "BEARISH_CRAB",
                 "BULLISH_BUTTERFLY", "BEARISH_BUTTERFLY"
                 -> new String[]{"X", "A", "B", "C", "D"}[Math.min(idx, 4)];
            case "HNS", "REV_HNS" -> new String[]{"A(LS)", "B(N1)", "C(H)", "D(N2)", "E(RS)"}[Math.min(idx, 4)];
            case "TRIANGLE_ASC", "TRIANGLE_DESC", "TRIANGLE_SYM"
                 -> new String[]{"A", "B", "C", "D", "E", "F"}[Math.min(idx, 5)];
            case "DOUBLE_TOP", "DOUBLE_BOTTOM" -> new String[]{"A", "B", "C", "D"}[Math.min(idx, 3)];
            default -> "P" + idx;
        };
    }

    private void printGeometryDiagnostics(Detection d) {
        if (d.pivots.size() < 4) return;
        double p0 = d.pivots.get(0).price();
        double p1 = d.pivots.get(1).price();
        double p2 = d.pivots.get(2).price();
        double p3 = d.pivots.get(3).price();
        double leg01 = Math.abs(p1 - p0) / d.avgBarLength;
        double leg12 = Math.abs(p2 - p1) / d.avgBarLength;
        double leg23 = Math.abs(p3 - p2) / d.avgBarLength;
        System.out.printf("  legs (in avgBar units): 0→1=%.2f  1→2=%.2f  2→3=%.2f%n", leg01, leg12, leg23);
        if (d.pivots.size() >= 5) {
            double p4 = d.pivots.get(4).price();
            double leg34 = Math.abs(p4 - p3) / d.avgBarLength;
            System.out.printf("  3→4=%.2f%n", leg34);
        }
        // Fib ratios for harmonics
        if (d.pivots.size() == 5 && (d.patternType.contains("BAT") || d.patternType.contains("GARTLEY")
                || d.patternType.contains("CRAB") || d.patternType.contains("BUTTERFLY"))) {
            double xa = Math.abs(d.pivots.get(1).price() - d.pivots.get(0).price());
            double ab = Math.abs(d.pivots.get(2).price() - d.pivots.get(1).price());
            double bc = Math.abs(d.pivots.get(3).price() - d.pivots.get(2).price());
            System.out.printf("  Fib: AB/XA=%.3f  BC/AB=%.3f%n", ab/xa, bc/ab);
        }
    }

    // --- Detection collection (for random sampling test) ---
    private List<Detection> collectAllDetections(Path dir) throws IOException {
        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        List<Detection> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        try (var stream = Files.newDirectoryStream(dir, "*.csv")) {
            for (Path csv : stream) {
                String sym = csv.getFileName().toString().replace(".csv", "");
                BarSeries full = loadCsv(sym, csv);
                if (full.getBarCount() < LOOKBACK_WINDOW + 50) continue;
                for (int we = LOOKBACK_WINDOW; we <= full.getEndIndex(); we += SLIDE_STEP) {
                    BarSeries w = sliceUpTo(full, we);
                    List<PivotPoint> pivots = extractor.extract(w, PIVOT_BARS, PIVOT_BARS, PivotType.BOTH);
                    collectDetections(sym, full, w, pivots, seen, result);
                }
            }
        }
        return result;
    }

    private void collectDetections(String sym, BarSeries full, BarSeries window, List<PivotPoint> pivots,
                                   Set<String> seen, List<Detection> out) {
        captureSafe(sym, "BULLISH_ABCD", full, seen, out, () ->
            new AbcdBullishDetector().findAll(window, pivots, LOOKBACK_WINDOW));
        captureSafe(sym, "BULLISH_BAT", full, seen, out, () ->
            new BatBullishDetector().findAll(window, pivots, LOOKBACK_WINDOW));
        captureSafe(sym, "BULLISH_CRAB", full, seen, out, () ->
            new CrabBullishDetector().findAll(window, pivots, LOOKBACK_WINDOW));
        captureSafe(sym, "REV_HNS", full, seen, out, () ->
            new ReverseHnsClassicDetector().findAll(window, pivots, LOOKBACK_WINDOW));
        captureSafe(sym, "HNS", full, seen, out, () ->
            new HnsClassicDetector().findAll(window, pivots, LOOKBACK_WINDOW));
        captureSafe(sym, "DOUBLE_TOP", full, seen, out, () ->
            new DoubleTopClassicDetector().findAll(window, pivots, LOOKBACK_WINDOW));
        captureSafe(sym, "DOUBLE_BOTTOM", full, seen, out, () ->
            new DoubleBottomClassicDetector().findAll(window, pivots, LOOKBACK_WINDOW));
    }

    private <P extends ClassicPattern> void captureSafe(
            String sym, String type, BarSeries full, Set<String> seen,
            List<Detection> out, DetectorRunner<P> runner) {
        try {
            for (P p : runner.run()) {
                String key = sym + "|" + type + "|" + p.endBarIndex();
                if (!seen.add(key)) continue;
                if (p.endBarIndex() + 5 >= full.getBarCount()) continue;
                Detection d = new Detection();
                d.symbol = sym;
                d.patternType = type;
                d.endBarIndex = p.endBarIndex();
                d.endDate = full.getBar(p.endBarIndex()).getEndTime()
                        .atZone(ZoneId.of("Asia/Kolkata")).toLocalDate().toString();
                d.pivots = new ArrayList<>(p.pivots());
                d.avgBarLength = p.avgBarLength();
                double startClose = full.getBar(p.endBarIndex()).getClosePrice().doubleValue();
                int t = Math.min(p.endBarIndex() + LOOKAHEAD, full.getEndIndex());
                double endClose = full.getBar(t).getClosePrice().doubleValue();
                d.fwdMovePct = (endClose - startClose) / startClose * 100;
                out.add(d);
            }
        } catch (Exception ignored) {}
    }

    // --- Volatile-stock scan ---
    private static final String[] ALL_PATTERN_KEYS = {
            "TRIANGLE_ASC","TRIANGLE_DESC","TRIANGLE_SYM","HNS","REV_HNS",
            "DOUBLE_TOP","DOUBLE_BOTTOM","BULLISH_VCP","BEARISH_VCP",
            "BULLISH_FLAG","BEARISH_FLAG","UPTREND_LINE","DOWNTREND_LINE",
            "BULLISH_ABCD","BEARISH_ABCD","BULLISH_BAT","BEARISH_BAT",
            "BULLISH_GARTLEY","BEARISH_GARTLEY","BULLISH_CRAB","BEARISH_CRAB",
            "BULLISH_BUTTERFLY","BEARISH_BUTTERFLY"};

    private void runAllDetectors(Map<String, Stat> stats, String sym, Set<String> seen,
                                  BarSeries full, BarSeries window, List<PivotPoint> pivots) {
        scanOne(stats, sym, seen, full, "TRIANGLE_ASC", () -> new TriangleClassicDetector()
                .findAll(window, pivots, LOOKBACK_WINDOW).stream()
                .filter(p -> p.kind() == TrianglePattern.TriangleKind.ASCENDING).toList(), true);
        scanOne(stats, sym, seen, full, "TRIANGLE_DESC", () -> new TriangleClassicDetector()
                .findAll(window, pivots, LOOKBACK_WINDOW).stream()
                .filter(p -> p.kind() == TrianglePattern.TriangleKind.DESCENDING).toList(), false);
        scanOne(stats, sym, seen, full, "TRIANGLE_SYM", () -> new TriangleClassicDetector()
                .findAll(window, pivots, LOOKBACK_WINDOW).stream()
                .filter(p -> p.kind() == TrianglePattern.TriangleKind.SYMMETRIC).toList(), null);
        scanOne(stats, sym, seen, full, "HNS", () ->
                new HnsClassicDetector().findAll(window, pivots, LOOKBACK_WINDOW), false);
        scanOne(stats, sym, seen, full, "REV_HNS", () ->
                new ReverseHnsClassicDetector().findAll(window, pivots, LOOKBACK_WINDOW), true);
        scanOne(stats, sym, seen, full, "DOUBLE_TOP", () ->
                new DoubleTopClassicDetector().findAll(window, pivots, LOOKBACK_WINDOW), false);
        scanOne(stats, sym, seen, full, "DOUBLE_BOTTOM", () ->
                new DoubleBottomClassicDetector().findAll(window, pivots, LOOKBACK_WINDOW), true);
        scanOne(stats, sym, seen, full, "BULLISH_VCP", () ->
                new BullishVcpDetector().findAll(window, pivots, LOOKBACK_WINDOW), true);
        scanOne(stats, sym, seen, full, "BEARISH_VCP", () ->
                new BearishVcpDetector().findAll(window, pivots, LOOKBACK_WINDOW), false);
        scanOne(stats, sym, seen, full, "BULLISH_FLAG", () ->
                new BullishFlagClassicDetector().findAll(window, pivots, LOOKBACK_WINDOW), true);
        scanOne(stats, sym, seen, full, "BEARISH_FLAG", () ->
                new BearishFlagClassicDetector().findAll(window, pivots, LOOKBACK_WINDOW), false);
        scanOne(stats, sym, seen, full, "UPTREND_LINE", () ->
                new UptrendLineDetector().findAll(window, pivots, LOOKBACK_WINDOW), true);
        scanOne(stats, sym, seen, full, "DOWNTREND_LINE", () ->
                new DowntrendLineDetector().findAll(window, pivots, LOOKBACK_WINDOW), false);
        scanOne(stats, sym, seen, full, "BULLISH_ABCD", () ->
                new AbcdBullishDetector().findAll(window, pivots, LOOKBACK_WINDOW), true);
        scanOne(stats, sym, seen, full, "BEARISH_ABCD", () ->
                new AbcdBearishDetector().findAll(window, pivots, LOOKBACK_WINDOW), false);
        scanOne(stats, sym, seen, full, "BULLISH_BAT", () ->
                new BatBullishDetector().findAll(window, pivots, LOOKBACK_WINDOW), true);
        scanOne(stats, sym, seen, full, "BEARISH_BAT", () ->
                new BatBearishDetector().findAll(window, pivots, LOOKBACK_WINDOW), false);
        scanOne(stats, sym, seen, full, "BULLISH_GARTLEY", () ->
                new GartleyBullishDetector().findAll(window, pivots, LOOKBACK_WINDOW), true);
        scanOne(stats, sym, seen, full, "BEARISH_GARTLEY", () ->
                new GartleyBearishDetector().findAll(window, pivots, LOOKBACK_WINDOW), false);
        scanOne(stats, sym, seen, full, "BULLISH_CRAB", () ->
                new CrabBullishDetector().findAll(window, pivots, LOOKBACK_WINDOW), true);
        scanOne(stats, sym, seen, full, "BEARISH_CRAB", () ->
                new CrabBearishDetector().findAll(window, pivots, LOOKBACK_WINDOW), false);
        scanOne(stats, sym, seen, full, "BULLISH_BUTTERFLY", () ->
                new ButterflyBullishDetector().findAll(window, pivots, LOOKBACK_WINDOW), true);
        scanOne(stats, sym, seen, full, "BEARISH_BUTTERFLY", () ->
                new ButterflyBearishDetector().findAll(window, pivots, LOOKBACK_WINDOW), false);
    }

    private <P extends ClassicPattern> void scanOne(
            Map<String, Stat> stats, String sym, Set<String> seen, BarSeries full,
            String key, DetectorRunner<P> runner, Boolean expectedBullish) {
        try {
            for (P p : runner.run()) {
                String dedup = sym + "|" + key + "|" + p.endBarIndex();
                if (!seen.add(dedup)) continue;
                int end = p.endBarIndex();
                if (end + 5 >= full.getBarCount()) continue;
                Stat s = stats.get(key);
                double startClose = full.getBar(end).getClosePrice().doubleValue();
                int t = Math.min(end + LOOKAHEAD, full.getEndIndex());
                double endClose = full.getBar(t).getClosePrice().doubleValue();
                double mv = (endClose - startClose) / startClose * 100;
                s.total++;
                s.movesTotal += mv;
                s.allMoves.add(mv);
                if (expectedBullish == null) continue;
                s.directed++;
                boolean correct = expectedBullish ? mv >= 1.0 : mv <= -1.0;
                if (correct) s.correct++;
            }
        } catch (Exception ignored) {}
    }

    private void printStatsTable(Map<String, Stat> stats) {
        System.out.printf("  %-22s %7s %12s %10s %10s%n", "pattern", "n", "dir_correct%", "mean_move", "median_move");
        System.out.println("  " + "-".repeat(70));
        for (var e : stats.entrySet()) {
            Stat s = e.getValue();
            if (s.total == 0) {
                System.out.printf("  %-22s %7d %12s %10s %10s%n", e.getKey(), 0, "—", "—", "—");
                continue;
            }
            double acc = s.directed == 0 ? 0 : 100.0 * s.correct / s.directed;
            double meanMv = s.movesTotal / s.total;
            Collections.sort(s.allMoves);
            double medMv = s.allMoves.get(s.allMoves.size() / 2);
            System.out.printf("  %-22s %7d %11.0f%% %+9.2f%% %+9.2f%%%n",
                    e.getKey(), s.total, acc, meanMv, medMv);
        }
    }

    private BarSeries sliceUpTo(BarSeries source, int endIdx) {
        BarSeries copy = new BaseBarSeriesBuilder().withName(source.getName() + "_to" + endIdx).build();
        int end = Math.min(source.getEndIndex(), endIdx);
        for (int i = source.getBeginIndex(); i <= end; i++) {
            Bar src = source.getBar(i);
            copy.addBar(BarsLoader.getBar(
                    src.getOpenPrice().doubleValue(), src.getHighPrice().doubleValue(),
                    src.getLowPrice().doubleValue(), src.getClosePrice().doubleValue(),
                    src.getVolume().doubleValue(), src.getEndTime(), Duration.ofHours(1)));
        }
        return copy;
    }

    private interface DetectorRunner<P> { List<P> run() throws Exception; }

    private static class Detection {
        String symbol; String patternType; int endBarIndex; String endDate;
        List<PivotPoint> pivots; double avgBarLength; double fwdMovePct;
    }
    private static class Stat {
        int total = 0, directed = 0, correct = 0;
        double movesTotal = 0;
        List<Double> allMoves = new ArrayList<>();
    }

    private BarSeries loadCsv(String sym, Path csv) throws IOException {
        BarSeries series = new BaseBarSeriesBuilder().withName(sym).build();
        List<String> lines = Files.readAllLines(csv);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim(); if (line.isEmpty()) continue;
            String[] parts = line.split(","); if (parts.length < 6) continue;
            String ts = parts[0]; if (!ts.endsWith("Z")) ts += "Z";
            series.addBar(BarsLoader.getBar(
                    Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]), Double.parseDouble(parts[4]),
                    Double.parseDouble(parts[5]), Instant.parse(ts), Duration.ofHours(1)));
        }
        return series;
    }
}
