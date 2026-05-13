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
 * Trailing stop simulation for HNS/REV_HNS patterns detected via CPZZ pivots.
 *
 * Compares fixed-target exit (baseline) vs trailing-stop exit strategy.
 *
 * Strategy rules (same entry for both arms):
 *   - Detect HNS/REV_HNS, compute neckline = mean(B price, D price)
 *   - Wait for breakout (close crosses neckline)
 *   - Wait for retest (bar range touches within 0.5% of neckline)
 *   - Entry: at the retest bar close, in the breakout direction
 *   - Stop: head price (above neckline for HNS, below for REV_HNS)
 *
 * Exit differences:
 *   FIXED TARGET (baseline):
 *     - Target = neckline + pattern_height in breakout direction
 *     - Exit on target hit or stop hit or time (30 bars)
 *
 *   TRAILING STOP:
 *     - Initial stop = head price
 *     - When price reaches 1x target (neckline + pattern_height), move stop to entry (breakeven)
 *     - Continue: if price moves another 0.5x height favorably, move stop to +0.5x height from entry
 *     - Continue holding until stop hit or time-out (30 bars)
 *
 * Metrics: trades fired, win rate, mean P/L per trade, sum P/L
 */
class CpzzTrailingStopSimulationTest {

    private static final Path DATA_DIR = Paths.get("/tmp/hourly-scan-bars");
    private static final int LOOKBACK_WINDOW = 200;
    private static final int SLIDE_STEP = 100;
    private static final double RETEST_TOLERANCE_PCT = 0.5;
    private static final int MAX_BARS_TO_BREAKOUT = 30;
    private static final int MAX_BARS_TO_RETEST = 30;
    private static final int TIME_STOP_BARS = 30;

    @Test
    void simulateTrailingStopVsFixedTargetOnHnsRetests() throws IOException {
        Assumptions.assumeTrue(Files.exists(DATA_DIR), "Hourly scan data missing");

        SimulationResult baseline = new SimulationResult("FixedTarget");
        SimulationResult trailing = new SimulationResult("TrailingStop");

        try (var stream = Files.newDirectoryStream(DATA_DIR, "*.csv")) {
            for (Path csv : stream) {
                String sym = csv.getFileName().toString().replace(".csv", "");
                BarSeries full = loadCsv(sym, csv);
                if (full.getBarCount() < LOOKBACK_WINDOW + 50) continue;

                List<PivotPoint> cpzzPivots = runCpzz(full);
                Set<String> baselineSeen = new HashSet<>();
                Set<String> trailingSeen = new HashSet<>();

                simulateDetections(full, cpzzPivots, sym, baselineSeen, baseline, /*trailing=*/ false);
                simulateDetections(full, cpzzPivots, sym, trailingSeen, trailing, /*trailing=*/ true);
            }
        }

        printResults(baseline);
        printResults(trailing);
        printComparison(baseline, trailing);

        assertFalse(baseline.tradesFired == 0 && trailing.tradesFired == 0,
                "Both simulations produced no trades — pipeline broken");
    }

    private void simulateDetections(BarSeries full, List<PivotPoint> pivots,
                                     String sym, Set<String> seen,
                                     SimulationResult result, boolean trailing) {
        try {
            for (HnsPattern p : new HnsClassicDetector().findAll(full, pivots, full.getBarCount())) {
                tryTrade(sym, "HNS", p, full, seen, result, false, trailing);
            }
            for (ReverseHnsPattern p : new ReverseHnsClassicDetector().findAll(full, pivots, full.getBarCount())) {
                tryTrade(sym, "REV_HNS", p, full, seen, result, true, trailing);
            }
        } catch (Exception ignored) {}
    }

    private void tryTrade(String sym, String type, ClassicPattern p, BarSeries full,
                          Set<String> seen, SimulationResult result,
                          boolean bullish, boolean useTrailingStop) {
        int eBar = p.pivots().get(4).barIndex();
        String key = sym + "|" + type + "|" + eBar;
        if (!seen.add(key)) return;

        result.detections++;

        double bPrice = p.pivots().get(1).price();
        double dPrice = p.pivots().get(3).price();
        double headPrice = p.pivots().get(2).price();
        double neckline = (bPrice + dPrice) / 2.0;
        double height = Math.abs(headPrice - neckline);
        double target = bullish ? neckline + height : neckline - height;
        double stop = headPrice;

        // Find breakout bar
        int breakoutBar = -1;
        int maxBreakout = Math.min(eBar + MAX_BARS_TO_BREAKOUT, full.getEndIndex());
        for (int i = eBar + 1; i <= maxBreakout; i++) {
            double c = full.getBar(i).getClosePrice().doubleValue();
            if ((bullish && c > neckline) || (!bullish && c < neckline)) {
                breakoutBar = i;
                break;
            }
        }
        if (breakoutBar < 0) {
            result.noBreakout++;
            return;
        }

        // Find retest bar
        int retestBar = -1;
        double tolerance = neckline * RETEST_TOLERANCE_PCT / 100.0;
        int maxRetest = Math.min(breakoutBar + MAX_BARS_TO_RETEST, full.getEndIndex());
        for (int i = breakoutBar + 1; i <= maxRetest; i++) {
            Bar bar = full.getBar(i);
            double hi = bar.getHighPrice().doubleValue();
            double lo = bar.getLowPrice().doubleValue();
            if (lo <= neckline + tolerance && hi >= neckline - tolerance) {
                retestBar = i;
                break;
            }
        }
        if (retestBar < 0) {
            result.noRetest++;
            return;
        }

        double entry = full.getBar(retestBar).getClosePrice().doubleValue();
        int exitBar = -1;
        double exitPrice = entry;
        String exitReason = "TIMEOUT";

        if (useTrailingStop) {
            // Trailing stop logic
            exitBar = simulateTrailingStop(full, retestBar, bullish, entry, neckline, height, stop);
            if (exitBar > 0) {
                exitPrice = full.getBar(exitBar).getClosePrice().doubleValue();
                // Determine exit reason by walking through the bars
                exitReason = determineExitReason(full, retestBar, exitBar, bullish, entry, neckline, height, stop);
            }
        } else {
            // Fixed target logic (baseline)
            int maxBar = Math.min(retestBar + TIME_STOP_BARS, full.getEndIndex());
            for (int i = retestBar + 1; i <= maxBar; i++) {
                Bar bar = full.getBar(i);
                double hi = bar.getHighPrice().doubleValue();
                double lo = bar.getLowPrice().doubleValue();
                if (bullish) {
                    if (lo <= stop) { exitBar = i; exitPrice = stop; exitReason = "STOP"; break; }
                    if (hi >= target) { exitBar = i; exitPrice = target; exitReason = "TARGET"; break; }
                } else {
                    if (hi >= stop) { exitBar = i; exitPrice = stop; exitReason = "STOP"; break; }
                    if (lo <= target) { exitBar = i; exitPrice = target; exitReason = "TARGET"; break; }
                }
            }
            if (exitBar < 0) {
                exitBar = maxBar;
                exitPrice = full.getBar(maxBar).getClosePrice().doubleValue();
            }
        }

        double pnlPct = bullish ? (exitPrice - entry) / entry * 100 : (entry - exitPrice) / entry * 100;
        boolean win = pnlPct > 0;

        result.tradesFired++;
        if (win) result.wins++; else result.losses++;
        result.pnlSum += pnlPct;
        result.holdingBarsSum += (exitBar - retestBar);
        switch (exitReason) {
            case "TARGET" -> result.targetExits++;
            case "STOP" -> result.stopExits++;
            default -> result.timeoutExits++;
        }
    }

    /**
     * Simulate trailing stop exit. Returns the exit bar index or -1 if not found.
     */
    private int simulateTrailingStop(BarSeries series, int entryBar, boolean bullish,
                                      double entry, double neckline, double height, double initialStop) {
        double currentStop = initialStop;
        double targetPrice = bullish ? neckline + height : neckline - height;
        double halfHeightMove = height * 0.5;
        boolean targetHit = false;

        int maxBar = Math.min(entryBar + TIME_STOP_BARS, series.getEndIndex());
        for (int i = entryBar + 1; i <= maxBar; i++) {
            Bar bar = series.getBar(i);
            double hi = bar.getHighPrice().doubleValue();
            double lo = bar.getLowPrice().doubleValue();

            if (bullish) {
                // Check if target is hit
                if (!targetHit && hi >= targetPrice) {
                    targetHit = true;
                    currentStop = entry;  // Move stop to entry (breakeven)
                }

                // If we hit target and move another half-height, tighten stop
                if (targetHit && hi >= entry + halfHeightMove) {
                    currentStop = entry + halfHeightMove * 0.5;
                }

                // Check if stop is hit
                if (lo <= currentStop) {
                    return i;
                }
            } else {
                // Mirror logic for bearish
                if (!targetHit && lo <= targetPrice) {
                    targetHit = true;
                    currentStop = entry;  // Move stop to entry (breakeven)
                }

                if (targetHit && lo <= entry - halfHeightMove) {
                    currentStop = entry - halfHeightMove * 0.5;
                }

                if (hi >= currentStop) {
                    return i;
                }
            }
        }

        return maxBar;  // Time stop
    }

    /**
     * Determine the exit reason for trailing stop (for reporting purposes).
     */
    private String determineExitReason(BarSeries series, int entryBar, int exitBar,
                                       boolean bullish, double entry, double neckline,
                                       double height, double initialStop) {
        double targetPrice = bullish ? neckline + height : neckline - height;

        // Walk forward and check which event occurred first
        double currentStop = initialStop;
        boolean targetHit = false;
        double halfHeightMove = height * 0.5;

        for (int i = entryBar + 1; i <= exitBar; i++) {
            Bar bar = series.getBar(i);
            double hi = bar.getHighPrice().doubleValue();
            double lo = bar.getLowPrice().doubleValue();

            if (bullish) {
                if (!targetHit && hi >= targetPrice) {
                    if (i == exitBar) return "TARGET";
                    targetHit = true;
                    currentStop = entry;
                }
                if (targetHit && hi >= entry + halfHeightMove) {
                    currentStop = entry + halfHeightMove * 0.5;
                }
                if (lo <= currentStop && i == exitBar) return "STOP";
            } else {
                if (!targetHit && lo <= targetPrice) {
                    if (i == exitBar) return "TARGET";
                    targetHit = true;
                    currentStop = entry;
                }
                if (targetHit && lo <= entry - halfHeightMove) {
                    currentStop = entry - halfHeightMove * 0.5;
                }
                if (hi >= currentStop && i == exitBar) return "STOP";
            }
        }

        return "TIMEOUT";
    }

    private List<PivotPoint> runCpzz(BarSeries series) {
        ZigZagParams params = ZigZagParams.ofDefaults(
                14, 1.0, 0.005, 1.0, 1, false, 1.0, 14,
                ZigZagParams.Mode.BACKTEST);
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

    private void printResults(SimulationResult r) {
        System.out.printf("%n========== %s ==========%n", r.name);
        System.out.printf("  Detections:         %5d%n", r.detections);
        System.out.printf("    No breakout:      %5d%n", r.noBreakout);
        System.out.printf("    No retest:        %5d%n", r.noRetest);
        System.out.printf("  Trades fired:       %5d%n", r.tradesFired);
        if (r.tradesFired > 0) {
            System.out.printf("    Wins:             %5d (%.0f%%)%n", r.wins, 100.0 * r.wins / r.tradesFired);
            System.out.printf("    Losses:           %5d%n", r.losses);
            System.out.printf("    Target exits:     %5d (%.0f%%)%n",
                    r.targetExits, 100.0 * r.targetExits / r.tradesFired);
            System.out.printf("    Stop exits:       %5d (%.0f%%)%n",
                    r.stopExits, 100.0 * r.stopExits / r.tradesFired);
            System.out.printf("    Timeout exits:    %5d (%.0f%%)%n",
                    r.timeoutExits, 100.0 * r.timeoutExits / r.tradesFired);
            System.out.printf("  Mean P/L per trade: %+.2f%%%n", r.pnlSum / r.tradesFired);
            System.out.printf("  Total P/L:          %+.2f%%%n", r.pnlSum);
            System.out.printf("  Mean holding:       %.1f bars%n",
                    (double) r.holdingBarsSum / r.tradesFired);
        }
    }

    private void printComparison(SimulationResult baseline, SimulationResult trailing) {
        System.out.printf("%n========== TRAILING STOP vs FIXED TARGET COMPARISON ==========%n");
        System.out.printf("  %-22s %15s %15s%n", "metric", "FixedTarget", "TrailingStop");
        System.out.printf("  %-22s %15d %15d%n", "detections", baseline.detections, trailing.detections);
        System.out.printf("  %-22s %15d %15d%n", "trades fired", baseline.tradesFired, trailing.tradesFired);
        if (baseline.tradesFired > 0 && trailing.tradesFired > 0) {
            System.out.printf("  %-22s %14.0f%% %14.0f%%%n", "win rate",
                    100.0 * baseline.wins / baseline.tradesFired, 100.0 * trailing.wins / trailing.tradesFired);
            System.out.printf("  %-22s %+13.2f%% %+13.2f%%%n", "mean P/L per trade",
                    baseline.pnlSum / baseline.tradesFired, trailing.pnlSum / trailing.tradesFired);
            System.out.printf("  %-22s %+13.2f%% %+13.2f%%%n", "total P/L",
                    baseline.pnlSum, trailing.pnlSum);
        }
        System.out.println();
    }

    private static class SimulationResult {
        final String name;
        int detections = 0, noBreakout = 0, noRetest = 0;
        int tradesFired = 0, wins = 0, losses = 0;
        int targetExits = 0, stopExits = 0, timeoutExits = 0;
        double pnlSum = 0;
        int holdingBarsSum = 0;
        SimulationResult(String name) { this.name = name; }
    }
}
