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

/**
 * Empirical hit rate for the "measured-move" targets of each pattern:
 * classical (HNS, Reverse HNS, Double Top, Double Bottom) and harmonic
 * (ABCD, BAT, Gartley, Crab, Butterfly x Bullish/Bearish).
 *
 * Uses sliding-window scan (200-bar window, 100-bar slide) to avoid
 * full-series lookback rejection of DT/DB patterns.
 *
 * For classical patterns, target = neckline +/- pattern_height.
 * For harmonic patterns, target = 0.382 retrace of AD leg:
 *   Bullish: target = D + 0.382 * (A - D)
 *   Bearish: target = D - 0.382 * (D - A)
 *
 * Lookahead: 100 hourly bars (~14 trading days).
 *
 * @see CpzzVsWilliamsSimulationTest — uses a tighter trade-simulation window
 */
class ClassicalTargetHitRateTest {

    private static final Path DATA_DIR = Paths.get("/tmp/hourly-scan-bars");
    private static final int LOOKAHEAD_BARS = 100;
    private static final int WINDOW_SIZE = 200;
    private static final int SLIDE_STEP = 100;

    @Test
    void measureClassicalTargetHitRates() throws IOException {
        Assumptions.assumeTrue(Files.exists(DATA_DIR), "Hourly scan data missing");

        Map<String, Stat> stats = new LinkedHashMap<>();
        // Classical patterns
        for (String k : new String[]{"HNS", "REV_HNS", "DOUBLE_TOP", "DOUBLE_BOTTOM"}) {
            stats.put(k, new Stat());
        }
        // Harmonic patterns
        for (String k : new String[]{"BULLISH_ABCD", "BEARISH_ABCD", "BULLISH_BAT", "BEARISH_BAT",
                "BULLISH_GARTLEY", "BEARISH_GARTLEY", "BULLISH_CRAB", "BEARISH_CRAB",
                "BULLISH_BUTTERFLY", "BEARISH_BUTTERFLY"}) {
            stats.put(k, new Stat());
        }

        try (var stream = Files.newDirectoryStream(DATA_DIR, "*.csv")) {
            for (Path csv : stream) {
                String sym = csv.getFileName().toString().replace(".csv", "");
                BarSeries series = loadCsv(sym, csv);
                if (series.getBarCount() < WINDOW_SIZE + 50) continue;

                scanWithSlidingWindow(series, sym, stats);
            }
        }

        printResults(stats);

        assertFalse(stats.values().stream().allMatch(s -> s.n == 0),
                "Expected pattern detections");
    }

    private void scanWithSlidingWindow(BarSeries full, String sym, Map<String, Stat> stats) {
        Set<String> seen = new HashSet<>();

        // Sliding window: extract pivots per window, detect patterns
        for (int windowEnd = WINDOW_SIZE; windowEnd <= full.getEndIndex(); windowEnd += SLIDE_STEP) {
            BarSeries window = sliceUpTo(full, windowEnd);
            List<PivotPoint> pivots = runCpzz(window);

            // Classical patterns
            for (HnsPattern p : new HnsClassicDetector().findAll(window, pivots, WINDOW_SIZE)) {
                if (shouldSkip(sym, "HNS", p.endBarIndex(), seen)) continue;
                analyse("HNS", p.endBarIndex(),
                        avg(p.pivots().get(1).price(), p.pivots().get(3).price()),
                        p.pivots().get(2).price(),
                        false, full, stats.get("HNS"));
            }
            for (ReverseHnsPattern p : new ReverseHnsClassicDetector().findAll(window, pivots, WINDOW_SIZE)) {
                if (shouldSkip(sym, "REV_HNS", p.endBarIndex(), seen)) continue;
                analyse("REV_HNS", p.endBarIndex(),
                        avg(p.pivots().get(1).price(), p.pivots().get(3).price()),
                        p.pivots().get(2).price(),
                        true, full, stats.get("REV_HNS"));
            }
            for (DoubleTopPattern p : new DoubleTopClassicDetector().findAll(window, pivots, WINDOW_SIZE)) {
                if (shouldSkip(sym, "DOUBLE_TOP", p.endBarIndex(), seen)) continue;
                double peak = avg(p.pivots().get(0).price(), p.pivots().get(2).price());
                double neckline = p.pivots().get(1).price();
                analyse("DOUBLE_TOP", p.endBarIndex(), neckline, peak, false, full, stats.get("DOUBLE_TOP"));
            }
            for (DoubleBottomPattern p : new DoubleBottomClassicDetector().findAll(window, pivots, WINDOW_SIZE)) {
                if (shouldSkip(sym, "DOUBLE_BOTTOM", p.endBarIndex(), seen)) continue;
                double trough = avg(p.pivots().get(0).price(), p.pivots().get(2).price());
                double neckline = p.pivots().get(1).price();
                analyse("DOUBLE_BOTTOM", p.endBarIndex(), neckline, trough, true, full, stats.get("DOUBLE_BOTTOM"));
            }

            // Harmonic patterns
            for (AbcdPattern p : new AbcdBullishDetector().findAll(window, pivots, WINDOW_SIZE)) {
                if (shouldSkip(sym, "BULLISH_ABCD", p.endBarIndex(), seen)) continue;
                analyseHarmonic("BULLISH_ABCD", p.endBarIndex(),
                        p.pivots().get(0).price(), p.pivots().get(3).price(),
                        true, full, stats.get("BULLISH_ABCD"));
            }
            for (AbcdPattern p : new AbcdBearishDetector().findAll(window, pivots, WINDOW_SIZE)) {
                if (shouldSkip(sym, "BEARISH_ABCD", p.endBarIndex(), seen)) continue;
                analyseHarmonic("BEARISH_ABCD", p.endBarIndex(),
                        p.pivots().get(0).price(), p.pivots().get(3).price(),
                        false, full, stats.get("BEARISH_ABCD"));
            }
            for (BatPattern p : new BatBullishDetector().findAll(window, pivots, WINDOW_SIZE)) {
                if (shouldSkip(sym, "BULLISH_BAT", p.endBarIndex(), seen)) continue;
                analyseHarmonic("BULLISH_BAT", p.endBarIndex(),
                        p.pivots().get(0).price(), p.pivots().get(4).price(),
                        true, full, stats.get("BULLISH_BAT"));
            }
            for (BatPattern p : new BatBearishDetector().findAll(window, pivots, WINDOW_SIZE)) {
                if (shouldSkip(sym, "BEARISH_BAT", p.endBarIndex(), seen)) continue;
                analyseHarmonic("BEARISH_BAT", p.endBarIndex(),
                        p.pivots().get(0).price(), p.pivots().get(4).price(),
                        false, full, stats.get("BEARISH_BAT"));
            }
            for (GartleyPattern p : new GartleyBullishDetector().findAll(window, pivots, WINDOW_SIZE)) {
                if (shouldSkip(sym, "BULLISH_GARTLEY", p.endBarIndex(), seen)) continue;
                analyseHarmonic("BULLISH_GARTLEY", p.endBarIndex(),
                        p.pivots().get(0).price(), p.pivots().get(4).price(),
                        true, full, stats.get("BULLISH_GARTLEY"));
            }
            for (GartleyPattern p : new GartleyBearishDetector().findAll(window, pivots, WINDOW_SIZE)) {
                if (shouldSkip(sym, "BEARISH_GARTLEY", p.endBarIndex(), seen)) continue;
                analyseHarmonic("BEARISH_GARTLEY", p.endBarIndex(),
                        p.pivots().get(0).price(), p.pivots().get(4).price(),
                        false, full, stats.get("BEARISH_GARTLEY"));
            }
            for (CrabPattern p : new CrabBullishDetector().findAll(window, pivots, WINDOW_SIZE)) {
                if (shouldSkip(sym, "BULLISH_CRAB", p.endBarIndex(), seen)) continue;
                analyseHarmonic("BULLISH_CRAB", p.endBarIndex(),
                        p.pivots().get(0).price(), p.pivots().get(4).price(),
                        true, full, stats.get("BULLISH_CRAB"));
            }
            for (CrabPattern p : new CrabBearishDetector().findAll(window, pivots, WINDOW_SIZE)) {
                if (shouldSkip(sym, "BEARISH_CRAB", p.endBarIndex(), seen)) continue;
                analyseHarmonic("BEARISH_CRAB", p.endBarIndex(),
                        p.pivots().get(0).price(), p.pivots().get(4).price(),
                        false, full, stats.get("BEARISH_CRAB"));
            }
            for (ButterflyPattern p : new ButterflyBullishDetector().findAll(window, pivots, WINDOW_SIZE)) {
                if (shouldSkip(sym, "BULLISH_BUTTERFLY", p.endBarIndex(), seen)) continue;
                analyseHarmonic("BULLISH_BUTTERFLY", p.endBarIndex(),
                        p.pivots().get(0).price(), p.pivots().get(4).price(),
                        true, full, stats.get("BULLISH_BUTTERFLY"));
            }
            for (ButterflyPattern p : new ButterflyBearishDetector().findAll(window, pivots, WINDOW_SIZE)) {
                if (shouldSkip(sym, "BEARISH_BUTTERFLY", p.endBarIndex(), seen)) continue;
                analyseHarmonic("BEARISH_BUTTERFLY", p.endBarIndex(),
                        p.pivots().get(0).price(), p.pivots().get(4).price(),
                        false, full, stats.get("BEARISH_BUTTERFLY"));
            }
        }
    }

    private boolean shouldSkip(String sym, String type, int endBar, Set<String> seen) {
        return !seen.add(sym + "|" + type + "|" + endBar);
    }

    private void analyse(String key, int endBar, double neckline, double extreme,
                          boolean bullish, BarSeries series, Stat s) {
        if (endBar + 5 >= series.getBarCount()) return;
        double height = Math.abs(extreme - neckline);
        double target = bullish ? neckline + height : neckline - height;
        int maxBar = Math.min(endBar + LOOKAHEAD_BARS, series.getEndIndex());

        boolean hit = false;
        int barsToTarget = -1;
        double bestExtreme = bullish ? Double.MIN_VALUE : Double.MAX_VALUE;
        for (int i = endBar + 1; i <= maxBar; i++) {
            Bar bar = series.getBar(i);
            double hi = bar.getHighPrice().doubleValue();
            double lo = bar.getLowPrice().doubleValue();
            if (bullish) {
                bestExtreme = Math.max(bestExtreme, hi);
                if (!hit && hi >= target) { hit = true; barsToTarget = i - endBar; }
            } else {
                bestExtreme = Math.min(bestExtreme, lo);
                if (!hit && lo <= target) { hit = true; barsToTarget = i - endBar; }
            }
        }

        double startClose = series.getBar(endBar).getClosePrice().doubleValue();
        double movePct = bullish ? (bestExtreme - startClose) / startClose * 100
                                  : (startClose - bestExtreme) / startClose * 100;
        s.n++;
        s.allHeights.add(height / neckline * 100);
        s.allMoves.add(movePct);
        if (hit) {
            s.hits++;
            s.barsToTarget.add(barsToTarget);
        }
    }

    private void analyseHarmonic(String key, int endBar, double aPrice, double dPrice,
                                  boolean bullish, BarSeries series, Stat s) {
        if (endBar + 5 >= series.getBarCount()) return;
        double adLeg = Math.abs(aPrice - dPrice);
        double target = bullish ? dPrice + 0.382 * (aPrice - dPrice)
                                : dPrice - 0.382 * (dPrice - aPrice);
        int maxBar = Math.min(endBar + LOOKAHEAD_BARS, series.getEndIndex());

        boolean hit = false;
        int barsToTarget = -1;
        double bestExtreme = bullish ? Double.MIN_VALUE : Double.MAX_VALUE;
        for (int i = endBar + 1; i <= maxBar; i++) {
            Bar bar = series.getBar(i);
            double hi = bar.getHighPrice().doubleValue();
            double lo = bar.getLowPrice().doubleValue();
            if (bullish) {
                bestExtreme = Math.max(bestExtreme, hi);
                if (!hit && hi >= target) { hit = true; barsToTarget = i - endBar; }
            } else {
                bestExtreme = Math.min(bestExtreme, lo);
                if (!hit && lo <= target) { hit = true; barsToTarget = i - endBar; }
            }
        }

        double startClose = series.getBar(endBar).getClosePrice().doubleValue();
        double movePct = bullish ? (bestExtreme - startClose) / startClose * 100
                                  : (startClose - bestExtreme) / startClose * 100;
        s.n++;
        s.allHeights.add(adLeg / dPrice * 100);
        s.allMoves.add(movePct);
        if (hit) {
            s.hits++;
            s.barsToTarget.add(barsToTarget);
        }
    }

    private double avg(double a, double b) { return (a + b) / 2.0; }

    private BarSeries sliceUpTo(BarSeries source, int endIdx) {
        BarSeries copy = new BaseBarSeriesBuilder().withName(source.getName()).build();
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

    private List<PivotPoint> runCpzz(BarSeries series) {
        ZigZagParams params = ZigZagParams.ofDefaults(
                14, 1.0, 0.005, 1.0, 1, false, 1.0, 14, ZigZagParams.Mode.BACKTEST);
        CandidatePivotZigZag cpzz = new CandidatePivotZigZag(params);
        for (int i = 0; i < series.getBarCount(); i++) cpzz.processBar(series, i);
        List<PivotPoint> result = new ArrayList<>();
        for (ZigZagPoint zp : cpzz.getConfirmedPivots()) {
            int barIndex = findBarByTimestamp(series, zp.getTimestamp());
            if (barIndex < 0) continue;
            PivotType type = zp.isHigh() ? PivotType.HIGH : PivotType.LOW;
            result.add(new PivotPoint(barIndex, zp.getTimestamp(), zp.getValue(), type));
        }
        result.sort(Comparator.comparingInt(PivotPoint::barIndex));
        return result;
    }

    private int findBarByTimestamp(BarSeries series, Instant ts) {
        int lo = series.getBeginIndex(), hi = series.getEndIndex();
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = series.getBar(mid).getEndTime().compareTo(ts);
            if (cmp == 0) return mid;
            if (cmp < 0) lo = mid + 1; else hi = mid - 1;
        }
        return -1;
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

    private void printResults(Map<String, Stat> stats) {
        System.out.println("\n========== CLASSICAL & HARMONIC TARGET HIT RATES (sliding-window scan, +100 bar lookahead) ==========\n");
        System.out.printf("  %-20s %8s %8s %12s %15s %12s%n",
                "pattern", "n", "hit %", "median bars", "median height", "median move");
        System.out.println("  " + "-".repeat(90));
        for (var e : stats.entrySet()) {
            Stat s = e.getValue();
            if (s.n == 0) {
                System.out.printf("  %-20s %8d %8s %12s %15s %12s%n", e.getKey(), 0, "—", "—", "—", "—");
                continue;
            }
            double hitRate = 100.0 * s.hits / s.n;
            Collections.sort(s.barsToTarget);
            int medianBars = s.barsToTarget.isEmpty() ? -1 : s.barsToTarget.get(s.barsToTarget.size() / 2);
            Collections.sort(s.allHeights);
            double medianHeight = s.allHeights.get(s.allHeights.size() / 2);
            Collections.sort(s.allMoves);
            double medianMove = s.allMoves.get(s.allMoves.size() / 2);
            System.out.printf("  %-20s %8d %7.0f%% %12s %14.2f%% %+11.2f%%%n",
                    e.getKey(), s.n, hitRate,
                    medianBars < 0 ? "—" : String.valueOf(medianBars),
                    medianHeight, medianMove);
        }
        System.out.println();
        System.out.println("  hit %       = % of detections where the target was touched within lookahead");
        System.out.println("  median bars = of trades that DID hit target, how long it took");
        System.out.println("  median move = of ALL detections, the actual % move from endBar to most-extreme-bar in pattern direction");
        System.out.println();
    }

    private static class Stat {
        int n = 0, hits = 0;
        List<Integer> barsToTarget = new ArrayList<>();
        List<Double> allHeights = new ArrayList<>();
        List<Double> allMoves = new ArrayList<>();
    }
}
