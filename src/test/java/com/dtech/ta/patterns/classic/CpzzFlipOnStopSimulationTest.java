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
 * Stopped-out-flip strategy on HNS / Reverse-HNS retest entries.
 *
 * Original trade: enter at neckline retest, SL = nearest CPZZ pivot
 * (above for HNS short, below for REV_HNS long), trigger on close basis.
 *
 * When the SL fires, INSTEAD of just exiting flat, exit the original
 * position AND flip to the opposite direction. The premise: a close
 * beyond the structural CPZZ pivot is itself a directional signal in
 * the opposite direction.
 *
 * Flip targets tested:
 *   A: just exit (no flip) — baseline (= Arm C of CpzzPivotStopSimulationTest)
 *   B: flip + target at right-shoulder price (E pivot)
 *   C: flip + target at 1.0 × stop_distance from flip entry (1:1 R:R)
 *   D: flip + target at 1.272 × stop_distance (Fibonacci extension)
 *
 * Flip stop in B/C/D: original neckline retest price. If price closes
 * back to neckline, the flip thesis is dead (range-bound chop).
 *
 * Time stop: 30 bars for both legs combined.
 *
 * Total P/L = original-trade P/L + flip-trade P/L (when applicable).
 */
class CpzzFlipOnStopSimulationTest {

    private static final Path DATA_DIR = Paths.get("/tmp/hourly-scan-bars");
    private static final double RETEST_TOLERANCE_PCT = 0.5;
    private static final int MAX_BARS_TO_BREAKOUT = 30;
    private static final int MAX_BARS_TO_RETEST = 30;
    private static final int TIME_STOP_BARS = 30;

    @Test
    void compareFlipVariants() throws IOException {
        Assumptions.assumeTrue(Files.exists(DATA_DIR), "Hourly scan data missing");

        Arm noFlip = new Arm("A: No flip — baseline (exit on SL)");
        Arm flipRS = new Arm("B: Flip on SL, target=right-shoulder");
        Arm flip1x = new Arm("C: Flip on SL, target=1.0x stop distance (1:1)");
        Arm flip1_272 = new Arm("D: Flip on SL, target=1.272x stop distance");

        try (var stream = Files.newDirectoryStream(DATA_DIR, "*.csv")) {
            for (Path csv : stream) {
                String sym = csv.getFileName().toString().replace(".csv", "");
                BarSeries series = loadCsv(sym, csv);
                if (series.getBarCount() < 250) continue;

                List<PivotPoint> pivots = runCpzz(series);

                for (HnsPattern p : new HnsClassicDetector().findAll(series, pivots, series.getBarCount())) {
                    simulate(p, pivots, series, false, noFlip, flipRS, flip1x, flip1_272);
                }
                for (ReverseHnsPattern p : new ReverseHnsClassicDetector().findAll(series, pivots, series.getBarCount())) {
                    simulate(p, pivots, series, true, noFlip, flipRS, flip1x, flip1_272);
                }
            }
        }

        print(noFlip); print(flipRS); print(flip1x); print(flip1_272);

        System.out.println("\n========== COMPARISON ==========");
        System.out.printf("  %-50s %10s %10s %10s%n", "arm", "trades", "win%", "total P/L");
        for (Arm a : new Arm[]{noFlip, flipRS, flip1x, flip1_272}) {
            if (a.trades == 0) continue;
            System.out.printf("  %-50s %10d %9.0f%% %+9.2f%%%n",
                    truncate(a.name, 50), a.trades, 100.0 * a.wins / a.trades, a.pnlSum);
        }
        System.out.printf("%n  Flip-trade activations:%n");
        System.out.printf("    flipRS:    %d flip-legs (out of %d original stop-outs)%n",
                flipRS.flipLegs, flipRS.origStopOuts);
        System.out.printf("    flip1x:    %d flip-legs%n", flip1x.flipLegs);
        System.out.printf("    flip1.272: %d flip-legs%n", flip1_272.flipLegs);

        assertFalse(noFlip.trades == 0, "Baseline produced no trades");
    }

    private void simulate(ClassicPattern p, List<PivotPoint> pivots, BarSeries full, boolean bullishPattern,
                          Arm noFlip, Arm flipRS, Arm flip1x, Arm flip1_272) {
        int eBar = p.pivots().get(4).barIndex();
        if (eBar + 5 >= full.getBarCount()) return;

        double bPrice = p.pivots().get(1).price();
        double dPrice = p.pivots().get(3).price();
        double headPrice = p.pivots().get(2).price();
        double rsPrice = p.pivots().get(4).price();
        double neckline = (bPrice + dPrice) / 2.0;
        double height = Math.abs(headPrice - neckline);

        int breakoutBar = -1;
        int maxB = Math.min(eBar + MAX_BARS_TO_BREAKOUT, full.getEndIndex());
        for (int i = eBar + 1; i <= maxB; i++) {
            double c = full.getBar(i).getClosePrice().doubleValue();
            if ((bullishPattern && c > neckline) || (!bullishPattern && c < neckline)) {
                breakoutBar = i; break;
            }
        }
        if (breakoutBar < 0) return;

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

        double entry = full.getBar(retestBar).getClosePrice().doubleValue();
        double target = bullishPattern ? neckline + height : neckline - height;

        // CPZZ pivot stop (most recent matching pivot at or before retest)
        PivotType wantType = bullishPattern ? PivotType.LOW : PivotType.HIGH;
        Double pivotStop = null;
        for (int i = pivots.size() - 1; i >= 0; i--) {
            PivotPoint pp = pivots.get(i);
            if (pp.barIndex() > retestBar) continue;
            if (pp.type() == wantType) { pivotStop = pp.price(); break; }
        }
        if (pivotStop == null) return;

        // Original trade with CPZZ pivot SL + close trigger
        TradeResult orig = runTrade(retestBar, entry, bullishPattern, pivotStop, target,
                full, /*onClose*/ true, TIME_STOP_BARS);

        // Record into all arms
        recordOriginal(noFlip, orig);
        recordOriginal(flipRS, orig);
        recordOriginal(flip1x, orig);
        recordOriginal(flip1_272, orig);

        // If original stopped out, run flip variants
        if (orig == null) return;
        if (orig.reason.equals("STOP")) {
            int flipEntryBar = orig.exitBar;
            double flipEntry = orig.exitPrice;
            double stopDist = Math.abs(flipEntry - entry);  // distance from original entry to stop-out

            // Flip stop for all variants: original neckline (= near entry)
            // Time budget: remaining bars after orig.exitBar (up to retestBar + TIME_STOP_BARS)
            int budgetEnd = Math.min(retestBar + TIME_STOP_BARS, full.getEndIndex());
            int flipBarsAvailable = budgetEnd - flipEntryBar;
            if (flipBarsAvailable <= 0) return;

            // Variant B: target = right-shoulder price
            // For flipped direction: flip is LONG if original was SHORT (bullishPattern=false)
            boolean flipLong = !bullishPattern;
            if (flipLong && rsPrice > flipEntry) {
                TradeResult flip = runTrade(flipEntryBar, flipEntry, true, neckline, rsPrice,
                        full, true, flipBarsAvailable);
                recordFlip(flipRS, flip);
            } else if (!flipLong && rsPrice < flipEntry) {
                TradeResult flip = runTrade(flipEntryBar, flipEntry, false, neckline, rsPrice,
                        full, true, flipBarsAvailable);
                recordFlip(flipRS, flip);
            }

            // Variant C: target = 1.0 x stop distance from flip entry
            double t1x = flipLong ? flipEntry + 1.0 * stopDist : flipEntry - 1.0 * stopDist;
            TradeResult flip1 = runTrade(flipEntryBar, flipEntry, flipLong, neckline, t1x,
                    full, true, flipBarsAvailable);
            recordFlip(flip1x, flip1);

            // Variant D: target = 1.272 x stop distance from flip entry
            double t1272 = flipLong ? flipEntry + 1.272 * stopDist : flipEntry - 1.272 * stopDist;
            TradeResult flip1272 = runTrade(flipEntryBar, flipEntry, flipLong, neckline, t1272,
                    full, true, flipBarsAvailable);
            recordFlip(flip1_272, flip1272);

            flipRS.origStopOuts++;
            flip1x.origStopOuts++;
            flip1_272.origStopOuts++;
        }
    }

    private void recordOriginal(Arm arm, TradeResult t) {
        if (t == null) return;
        arm.trades++;
        if (t.pnlPct > 0) arm.wins++; else arm.losses++;
        arm.pnlSum += t.pnlPct;
    }

    private void recordFlip(Arm arm, TradeResult t) {
        if (t == null) return;
        // Flip trade is counted as ADDITIONAL position; doesn't replace the original
        arm.flipLegs++;
        arm.pnlSum += t.pnlPct;
        if (t.pnlPct > 0) arm.flipWins++; else arm.flipLosses++;
    }

    private TradeResult runTrade(int entryBar, double entry, boolean longSide, double stop,
                                  double target, BarSeries full, boolean onClose, int maxHoldBars) {
        if (longSide && (stop >= entry || target <= entry)) return null;
        if (!longSide && (stop <= entry || target >= entry)) return null;

        int exitBar = -1;
        double exitPrice = entry;
        String reason = "TIMEOUT";
        int maxBar = Math.min(entryBar + maxHoldBars, full.getEndIndex());
        for (int i = entryBar + 1; i <= maxBar; i++) {
            Bar bar = full.getBar(i);
            double hi = bar.getHighPrice().doubleValue();
            double lo = bar.getLowPrice().doubleValue();
            double cl = bar.getClosePrice().doubleValue();
            if (longSide) {
                boolean stopHit = onClose ? (cl <= stop) : (lo <= stop);
                if (stopHit) {
                    exitBar = i; exitPrice = onClose ? cl : stop; reason = "STOP"; break;
                }
                if (hi >= target) { exitBar = i; exitPrice = target; reason = "TARGET"; break; }
            } else {
                boolean stopHit = onClose ? (cl >= stop) : (hi >= stop);
                if (stopHit) {
                    exitBar = i; exitPrice = onClose ? cl : stop; reason = "STOP"; break;
                }
                if (lo <= target) { exitBar = i; exitPrice = target; reason = "TARGET"; break; }
            }
        }
        if (exitBar < 0) { exitBar = maxBar; exitPrice = full.getBar(maxBar).getClosePrice().doubleValue(); }
        double pnl = longSide ? (exitPrice - entry) / entry * 100 : (entry - exitPrice) / entry * 100;
        return new TradeResult(exitBar, exitPrice, pnl, reason);
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
        System.out.printf("  Original trades:    %5d%n", a.trades);
        System.out.printf("  Win rate:           %5.0f%%   (W=%d L=%d)%n",
                100.0 * a.wins / a.trades, a.wins, a.losses);
        if (a.flipLegs > 0) {
            System.out.printf("  Flip legs:          %5d   (orig stop-outs: %d)%n", a.flipLegs, a.origStopOuts);
            System.out.printf("  Flip win rate:      %5.0f%%   (W=%d L=%d)%n",
                    100.0 * a.flipWins / a.flipLegs, a.flipWins, a.flipLosses);
        }
        System.out.printf("  Total P/L:          %+.2f%%%n", a.pnlSum);
    }

    private String truncate(String s, int max) { return s.length() <= max ? s : s.substring(0, max); }

    private record TradeResult(int exitBar, double exitPrice, double pnlPct, String reason) {}

    private static class Arm {
        final String name;
        int trades = 0, wins = 0, losses = 0;
        int flipLegs = 0, flipWins = 0, flipLosses = 0, origStopOuts = 0;
        double pnlSum = 0;
        Arm(String name) { this.name = name; }
    }
}
