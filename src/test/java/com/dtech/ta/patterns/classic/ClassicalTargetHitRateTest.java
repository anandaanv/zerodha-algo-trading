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
 * Empirical hit rate for the classical "measured-move" targets of each
 * reversal pattern: HNS, Reverse HNS, Double Top, Double Bottom.
 *
 * For each detected pattern, compute the textbook target:
 *   HNS:           target = neckline - (head - neckline)
 *   Reverse HNS:   target = neckline + (neckline - head)
 *   Double Top:    target = neckline - (twin_peak - neckline)
 *   Double Bottom: target = neckline + (neckline - twin_trough)
 *
 * Then walk forward N bars and check whether the high/low actually
 * touched the target. Report:
 *   - Hit rate
 *   - Median bars-to-target
 *   - Per-pattern stats
 *
 * Lookahead: 100 hourly bars (~14 trading days).
 *
 * (Note: Cup and Handle is NOT in our port. Adding C&H detector is
 * separate work; flagged for follow-up.)
 *
 * @see CpzzVsWilliamsSimulationTest — uses a tighter trade-simulation window
 */
class ClassicalTargetHitRateTest {

    private static final Path DATA_DIR = Paths.get("/tmp/hourly-scan-bars");
    private static final int LOOKAHEAD_BARS = 100;

    @Test
    void measureClassicalTargetHitRates() throws IOException {
        Assumptions.assumeTrue(Files.exists(DATA_DIR), "Hourly scan data missing");

        Map<String, Stat> stats = new LinkedHashMap<>();
        for (String k : new String[]{"HNS", "REV_HNS", "DOUBLE_TOP", "DOUBLE_BOTTOM"}) {
            stats.put(k, new Stat());
        }

        try (var stream = Files.newDirectoryStream(DATA_DIR, "*.csv")) {
            for (Path csv : stream) {
                String sym = csv.getFileName().toString().replace(".csv", "");
                BarSeries series = loadCsv(sym, csv);
                if (series.getBarCount() < 250) continue;

                List<PivotPoint> pivots = runCpzz(series);

                for (HnsPattern p : new HnsClassicDetector().findAll(series, pivots, series.getBarCount())) {
                    analyse("HNS", p.endBarIndex(),
                            avg(p.pivots().get(1).price(), p.pivots().get(3).price()),  // neckline
                            p.pivots().get(2).price(),                                   // head
                            false, series, stats.get("HNS"));
                }
                for (ReverseHnsPattern p : new ReverseHnsClassicDetector().findAll(series, pivots, series.getBarCount())) {
                    analyse("REV_HNS", p.endBarIndex(),
                            avg(p.pivots().get(1).price(), p.pivots().get(3).price()),
                            p.pivots().get(2).price(),
                            true, series, stats.get("REV_HNS"));
                }
                for (DoubleTopPattern p : new DoubleTopClassicDetector().findAll(series, pivots, series.getBarCount())) {
                    // A and C are the twin peaks; B is the neckline trough; D is current bar
                    double peak = avg(p.pivots().get(0).price(), p.pivots().get(2).price());
                    double neckline = p.pivots().get(1).price();
                    analyse("DOUBLE_TOP", p.endBarIndex(), neckline, peak, false, series, stats.get("DOUBLE_TOP"));
                }
                for (DoubleBottomPattern p : new DoubleBottomClassicDetector().findAll(series, pivots, series.getBarCount())) {
                    double trough = avg(p.pivots().get(0).price(), p.pivots().get(2).price());
                    double neckline = p.pivots().get(1).price();
                    analyse("DOUBLE_BOTTOM", p.endBarIndex(), neckline, trough, true, series, stats.get("DOUBLE_BOTTOM"));
                }
            }
        }

        System.out.println("\n========== CLASSICAL TARGET HIT RATES (CPZZ, hourly, +100 bar lookahead) ==========\n");
        System.out.printf("  %-15s %8s %8s %12s %15s %12s%n",
                "pattern", "n", "hit %", "median bars", "median height", "median move");
        System.out.println("  " + "-".repeat(80));
        for (var e : stats.entrySet()) {
            Stat s = e.getValue();
            if (s.n == 0) {
                System.out.printf("  %-15s %8d %8s %12s %15s %12s%n", e.getKey(), 0, "—", "—", "—", "—");
                continue;
            }
            double hitRate = 100.0 * s.hits / s.n;
            Collections.sort(s.barsToTarget);
            int medianBars = s.barsToTarget.isEmpty() ? -1 : s.barsToTarget.get(s.barsToTarget.size() / 2);
            Collections.sort(s.allHeights);
            double medianHeight = s.allHeights.get(s.allHeights.size() / 2);
            Collections.sort(s.allMoves);
            double medianMove = s.allMoves.get(s.allMoves.size() / 2);
            System.out.printf("  %-15s %8d %7.0f%% %12s %14.2f%% %+11.2f%%%n",
                    e.getKey(), s.n, hitRate,
                    medianBars < 0 ? "—" : String.valueOf(medianBars),
                    medianHeight, medianMove);
        }
        System.out.println();
        System.out.println("  hit %       = % of detections where the measured-move target was touched within lookahead");
        System.out.println("  median bars = of trades that DID hit target, how long it took");
        System.out.println("  median move = of ALL detections, the actual % move from endBar to most-extreme-bar in pattern direction");
        System.out.println();
        System.out.println("  Note: Cup & Handle (CnH) detector is NOT in the port. Add separately if needed.");

        assertFalse(stats.values().stream().allMatch(s -> s.n == 0),
                "Expected pattern detections");
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

    private double avg(double a, double b) { return (a + b) / 2.0; }

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

    private static class Stat {
        int n = 0, hits = 0;
        List<Integer> barsToTarget = new ArrayList<>();
        List<Double> allHeights = new ArrayList<>();
        List<Double> allMoves = new ArrayList<>();
    }
}
