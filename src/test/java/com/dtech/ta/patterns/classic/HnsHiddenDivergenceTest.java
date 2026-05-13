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
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests the trader's refined hypothesis: at the neckline retest of an HNS /
 * Reverse-HNS pattern, if RSI shows a "hidden divergence" pattern relative to
 * the right shoulder, the retest is fake and we should take the REVERSE trade.
 *
 * Divergence is measured between the LEFT SHOULDER pivot (A) and the
 * RIGHT SHOULDER pivot (E) — i.e., across the full pattern formation.
 *
 *   HNS (bearish): A and E are both HIGH pivots at roughly equal price.
 *     If RSI at E is meaningfully HIGHER than RSI at A while price is
 *     similar or lower, momentum is increasing despite the bearish
 *     pattern shape → hidden bearish per Bulkowski but the trader reads
 *     it as "pattern won't follow through" → reverse to LONG.
 *
 *   REV_HNS (bullish): A and E are both LOW pivots.
 *     If RSI at E is meaningfully LOWER than RSI at A, momentum is
 *     decreasing despite the bullish pattern → reverse to SHORT.
 *
 * We measure BOTH interpretations as separate arms.
 *
 * RSI period: 14. Threshold: 5 RSI points minimum for "meaningful" divergence.
 *
 * Four arms:
 *   A: Original retest entry, no divergence filter (baseline +46.80%)
 *   B: At retest, if divergence present, take REVERSE; else take original.
 *      This is the trader's "fake-retest reversal" hypothesis.
 *   C: At retest, if divergence present, SKIP the trade entirely.
 *      Treats divergence as a confluence-required filter.
 *   D: Only enter when divergence is present (in original direction).
 *      Treats divergence as a positive confirmation.
 */
class HnsHiddenDivergenceTest {

    private static final Path DATA_DIR = Paths.get("/tmp/hourly-scan-bars");
    private static final double RETEST_TOLERANCE_PCT = 0.5;
    private static final int MAX_BARS_TO_BREAKOUT = 30;
    private static final int MAX_BARS_TO_RETEST = 30;
    private static final int TIME_STOP_BARS = 30;
    private static final int RSI_PERIOD = 14;
    private static final double DIVERGENCE_THRESHOLD_RSI = 5.0;

    @Test
    void compareDivergenceBasedReverseEntry() throws IOException {
        Assumptions.assumeTrue(Files.exists(DATA_DIR), "Hourly scan data missing");

        Arm baseline = new Arm("A: Original retest, no filter");
        Arm reverseOnDiv = new Arm("B: Reverse trade if divergence at retest");
        Arm skipOnDiv = new Arm("C: Skip trade if divergence at retest");
        Arm onlyWithDiv = new Arm("D: Only trade if divergence (original direction)");

        try (var stream = Files.newDirectoryStream(DATA_DIR, "*.csv")) {
            for (Path csv : stream) {
                String sym = csv.getFileName().toString().replace(".csv", "");
                BarSeries series = loadCsv(sym, csv);
                if (series.getBarCount() < 250) continue;

                RSIIndicator rsi = new RSIIndicator(new ClosePriceIndicator(series), RSI_PERIOD);
                List<PivotPoint> pivots = runCpzz(series);

                for (HnsPattern p : new HnsClassicDetector().findAll(series, pivots, series.getBarCount())) {
                    process(sym, "HNS", p, series, rsi, false, baseline, reverseOnDiv, skipOnDiv, onlyWithDiv);
                }
                for (ReverseHnsPattern p : new ReverseHnsClassicDetector().findAll(series, pivots, series.getBarCount())) {
                    process(sym, "REV_HNS", p, series, rsi, true, baseline, reverseOnDiv, skipOnDiv, onlyWithDiv);
                }
            }
        }

        print(baseline); print(reverseOnDiv); print(skipOnDiv); print(onlyWithDiv);
        printComparison(baseline, reverseOnDiv, skipOnDiv, onlyWithDiv);

        assertFalse(baseline.trades == 0 && reverseOnDiv.trades == 0,
                "No trades fired in any arm");
    }

    private void process(String sym, String type, ClassicPattern pattern, BarSeries full, RSIIndicator rsi,
                         boolean bullishPattern, Arm baseline, Arm reverseOnDiv,
                         Arm skipOnDiv, Arm onlyWithDiv) {
        int eBar = pattern.pivots().get(4).barIndex();
        if (eBar + 5 >= full.getBarCount()) return;

        double bPrice = pattern.pivots().get(1).price();
        double dPrice = pattern.pivots().get(3).price();
        double headPrice = pattern.pivots().get(2).price();
        double rsPrice = pattern.pivots().get(4).price();
        double neckline = (bPrice + dPrice) / 2.0;
        double height = Math.abs(headPrice - neckline);

        // Breakout
        int breakoutBar = -1;
        int maxB = Math.min(eBar + MAX_BARS_TO_BREAKOUT, full.getEndIndex());
        for (int i = eBar + 1; i <= maxB; i++) {
            double c = full.getBar(i).getClosePrice().doubleValue();
            if ((bullishPattern && c > neckline) || (!bullishPattern && c < neckline)) {
                breakoutBar = i; break;
            }
        }
        if (breakoutBar < 0) return;

        // Retest
        int retestBar = -1;
        double tolerance = neckline * RETEST_TOLERANCE_PCT / 100.0;
        int maxR = Math.min(breakoutBar + MAX_BARS_TO_RETEST, full.getEndIndex());
        for (int i = breakoutBar + 1; i <= maxR; i++) {
            Bar bar = full.getBar(i);
            if (bar.getLowPrice().doubleValue() <= neckline + tolerance
                    && bar.getHighPrice().doubleValue() >= neckline - tolerance) {
                retestBar = i; break;
            }
        }
        if (retestBar < 0) return;

        // RSI at left shoulder (A = pivots[0]) vs at right shoulder (E = pivots[4])
        int aBar = pattern.pivots().get(0).barIndex();
        double rsiAtA = rsi.getValue(aBar).doubleValue();
        double rsiAtE = rsi.getValue(eBar).doubleValue();

        // Divergence detection between A and E (start of LS to end of RS):
        //   HNS (price LS and RS both HIGH, comparable price):
        //     trader's reverse-trigger: RSI_E > RSI_A by threshold
        //     (momentum rising across pattern — bullish underneath)
        //   REV_HNS (price LS and RS both LOW, comparable price):
        //     trader's reverse-trigger: RSI_A > RSI_E by threshold
        //     (momentum falling across pattern — bearish underneath)
        boolean divergencePresent;
        if (bullishPattern) {
            divergencePresent = (rsiAtA - rsiAtE) > DIVERGENCE_THRESHOLD_RSI;
        } else {
            divergencePresent = (rsiAtE - rsiAtA) > DIVERGENCE_THRESHOLD_RSI;
        }

        double entry = full.getBar(retestBar).getClosePrice().doubleValue();
        double originalStop = headPrice;
        double originalTarget = bullishPattern ? neckline + height : neckline - height;
        double reverseStop = bullishPattern ? neckline + height : neckline - height;  // flipped
        double reverseTarget = headPrice;

        // Arm A: always original
        simulateTrade(baseline, sym, retestBar, entry, bullishPattern, originalStop, originalTarget, full);

        // Arm B: reverse if divergence, else original
        if (divergencePresent) {
            simulateTrade(reverseOnDiv, sym, retestBar, entry, !bullishPattern, reverseStop, reverseTarget, full);
        } else {
            simulateTrade(reverseOnDiv, sym, retestBar, entry, bullishPattern, originalStop, originalTarget, full);
        }

        // Arm C: skip if divergence, else original
        if (!divergencePresent) {
            simulateTrade(skipOnDiv, sym, retestBar, entry, bullishPattern, originalStop, originalTarget, full);
        }

        // Arm D: only trade if divergence, original direction
        if (divergencePresent) {
            simulateTrade(onlyWithDiv, sym, retestBar, entry, bullishPattern, originalStop, originalTarget, full);
        }
    }

    private void simulateTrade(Arm arm, String sym, int entryBar, double entry,
                               boolean longSide, double stop, double target, BarSeries full) {
        if (longSide && (stop >= entry || target <= entry)) { arm.skippedBadGeom++; return; }
        if (!longSide && (stop <= entry || target >= entry)) { arm.skippedBadGeom++; return; }

        int exitBar = -1;
        double exitPrice = entry;
        String reason = "TIMEOUT";
        int maxBar = Math.min(entryBar + TIME_STOP_BARS, full.getEndIndex());
        for (int i = entryBar + 1; i <= maxBar; i++) {
            Bar bar = full.getBar(i);
            double hi = bar.getHighPrice().doubleValue();
            double lo = bar.getLowPrice().doubleValue();
            if (longSide) {
                if (lo <= stop) { exitBar = i; exitPrice = stop; reason = "STOP"; break; }
                if (hi >= target) { exitBar = i; exitPrice = target; reason = "TARGET"; break; }
            } else {
                if (hi >= stop) { exitBar = i; exitPrice = stop; reason = "STOP"; break; }
                if (lo <= target) { exitBar = i; exitPrice = target; reason = "TARGET"; break; }
            }
        }
        if (exitBar < 0) {
            exitBar = maxBar;
            exitPrice = full.getBar(maxBar).getClosePrice().doubleValue();
        }
        double pnl = longSide ? (exitPrice - entry) / entry * 100 : (entry - exitPrice) / entry * 100;
        arm.trades++;
        if (pnl > 0) arm.wins++; else arm.losses++;
        arm.pnlSum += pnl;
        switch (reason) {
            case "TARGET" -> arm.targetExits++;
            case "STOP" -> arm.stopExits++;
            default -> arm.timeoutExits++;
        }
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

    private void print(Arm a) {
        System.out.printf("%n========== %s ==========%n", a.name);
        if (a.trades == 0) { System.out.println("  No trades fired."); return; }
        System.out.printf("  Trades:           %5d%n", a.trades);
        System.out.printf("  Win rate:         %5.0f%%   (W=%d L=%d)%n",
                100.0 * a.wins / a.trades, a.wins, a.losses);
        System.out.printf("  Target / Stop / Timeout: %d (%.0f%%) / %d (%.0f%%) / %d (%.0f%%)%n",
                a.targetExits, 100.0 * a.targetExits / a.trades,
                a.stopExits, 100.0 * a.stopExits / a.trades,
                a.timeoutExits, 100.0 * a.timeoutExits / a.trades);
        System.out.printf("  Mean P/L per trade: %+.2f%%%n", a.pnlSum / a.trades);
        System.out.printf("  Total P/L:          %+.2f%%%n", a.pnlSum);
    }

    private void printComparison(Arm a, Arm b, Arm c, Arm d) {
        System.out.println("\n========== H2H ==========");
        System.out.printf("  %-10s %8s %10s %12s %12s%n", "arm", "trades", "win%", "mean P/L", "total P/L");
        for (Arm x : new Arm[]{a, b, c, d}) {
            if (x.trades == 0) continue;
            System.out.printf("  %-10s %8d %9.0f%% %+11.2f%% %+11.2f%%%n",
                    x.name.substring(0, Math.min(10, x.name.length())),
                    x.trades, 100.0 * x.wins / x.trades, x.pnlSum / x.trades, x.pnlSum);
        }
    }

    private static class Arm {
        final String name;
        int trades = 0, wins = 0, losses = 0;
        int targetExits = 0, stopExits = 0, timeoutExits = 0;
        int skippedBadGeom = 0;
        double pnlSum = 0;
        Arm(String name) { this.name = name; }
    }
}
