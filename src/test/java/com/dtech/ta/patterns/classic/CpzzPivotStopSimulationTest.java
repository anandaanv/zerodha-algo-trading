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
 * Same HNS / REV_HNS retest-entry simulation as the prior CPZZ tests, but with
 * two corrections to the exit logic:
 *
 *   1. Stop is anchored at the MOST RECENT CPZZ pivot (low for long, high for
 *      short) at or before the entry bar, NOT at the pattern's head price.
 *      The CPZZ pivot is a structural level — if price closes through it the
 *      reversal trade is invalidated. Head price is often far away, giving an
 *      unnecessarily wide stop.
 *
 *   2. Stop trigger is on CLOSING basis only — the exit fires only when a
 *      bar's CLOSE crosses the stop level. Intrabar wicks don't trigger a
 *      stop-out. This matches how a discretionary trader using daily/hourly
 *      bars would manage risk: an intrabar spike below support that closes
 *      back inside isn't a real breakdown.
 *
 * Three arms for comparison:
 *   A: Head SL, intrabar trigger (legacy logic from earlier tests)
 *   B: CPZZ-pivot SL, intrabar trigger
 *   C: CPZZ-pivot SL, closing-basis trigger  (the right one)
 *
 * Target: fixed 1x pattern height (same as baseline; trailing-stop variant
 * is a separate test).
 */
class CpzzPivotStopSimulationTest {

    private static final Path DATA_DIR = Paths.get("/tmp/hourly-scan-bars");
    private static final double RETEST_TOLERANCE_PCT = 0.5;
    private static final int MAX_BARS_TO_BREAKOUT = 30;
    private static final int MAX_BARS_TO_RETEST = 30;
    private static final int TIME_STOP_BARS = 30;

    @Test
    void compareCpzzPivotStopVariants() throws IOException {
        Assumptions.assumeTrue(Files.exists(DATA_DIR), "Hourly scan data missing");

        Arm headIntrabar = new Arm("A: Head SL + intrabar trigger (legacy)");
        Arm pivotIntrabar = new Arm("B: CPZZ-pivot SL + intrabar trigger");
        Arm pivotClose = new Arm("C: CPZZ-pivot SL + close trigger");
        Arm headClose = new Arm("D: Head SL + close trigger");
        Arm pivotCloseScaledTarget = new Arm("E: CPZZ-pivot SL + close trigger + 2x-stop target");
        Arm rsClose = new Arm("F: Right-shoulder SL + close trigger");
        Arm rsCloseScaledTarget = new Arm("G: Right-shoulder SL + close + 2x-stop target");

        try (var stream = Files.newDirectoryStream(DATA_DIR, "*.csv")) {
            for (Path csv : stream) {
                String sym = csv.getFileName().toString().replace(".csv", "");
                BarSeries series = loadCsv(sym, csv);
                if (series.getBarCount() < 250) continue;

                List<PivotPoint> pivots = runCpzz(series);

                for (HnsPattern p : new HnsClassicDetector().findAll(series, pivots, series.getBarCount())) {
                    simulate(sym, p, pivots, series, false,
                            headIntrabar, pivotIntrabar, pivotClose, headClose, pivotCloseScaledTarget,
                            rsClose, rsCloseScaledTarget);
                }
                for (ReverseHnsPattern p : new ReverseHnsClassicDetector().findAll(series, pivots, series.getBarCount())) {
                    simulate(sym, p, pivots, series, true,
                            headIntrabar, pivotIntrabar, pivotClose, headClose, pivotCloseScaledTarget,
                            rsClose, rsCloseScaledTarget);
                }
            }
        }

        print(headIntrabar); print(pivotIntrabar); print(pivotClose);
        print(headClose); print(pivotCloseScaledTarget);
        print(rsClose); print(rsCloseScaledTarget);
        System.out.println("\n========== COMPARISON ==========");
        System.out.printf("  %-50s %10s %10s %10s%n", "arm", "trades", "win%", "total P/L");
        for (Arm a : new Arm[]{headIntrabar, pivotIntrabar, pivotClose, headClose,
                pivotCloseScaledTarget, rsClose, rsCloseScaledTarget}) {
            if (a.trades == 0) continue;
            System.out.printf("  %-50s %10d %9.0f%% %+9.2f%%%n",
                    truncate(a.name, 50), a.trades, 100.0 * a.wins / a.trades, a.pnlSum);
        }

        assertFalse(pivotClose.trades == 0, "Pivot-close arm produced no trades");
    }

    private void simulate(String sym, ClassicPattern p, List<PivotPoint> pivots, BarSeries full,
                          boolean bullishPattern, Arm headIntrabar, Arm pivotIntrabar, Arm pivotClose,
                          Arm headClose, Arm pivotCloseScaledTarget,
                          Arm rsClose, Arm rsCloseScaledTarget) {
        int eBar = p.pivots().get(4).barIndex();
        if (eBar + 5 >= full.getBarCount()) return;

        double bPrice = p.pivots().get(1).price();
        double dPrice = p.pivots().get(3).price();
        double headPrice = p.pivots().get(2).price();
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

        double entry = full.getBar(retestBar).getClosePrice().doubleValue();
        double target = bullishPattern ? neckline + height : neckline - height;

        // Locate CPZZ-anchored stop
        // For LONG (bullish): nearest LOW pivot at or before retestBar — that's the structural support
        // For SHORT (bearish): nearest HIGH pivot at or before retestBar
        PivotType wantType = bullishPattern ? PivotType.LOW : PivotType.HIGH;
        Double pivotStop = null;
        for (int i = pivots.size() - 1; i >= 0; i--) {
            PivotPoint pp = pivots.get(i);
            if (pp.barIndex() > retestBar) continue;
            if (pp.type() == wantType) { pivotStop = pp.price(); break; }
        }
        if (pivotStop == null) return;

        // Right shoulder price — the structural invalidation level. For HNS short, this is
        // the right-shoulder HIGH (E pivot); for REV_HNS long, the right-shoulder LOW.
        double rsPrice = p.pivots().get(4).price();

        // Run all simulations
        simulateTrade(headIntrabar, retestBar, entry, bullishPattern, headPrice, target, full, /*onClose*/ false);
        simulateTrade(pivotIntrabar, retestBar, entry, bullishPattern, pivotStop, target, full, /*onClose*/ false);
        simulateTrade(pivotClose, retestBar, entry, bullishPattern, pivotStop, target, full, /*onClose*/ true);
        simulateTrade(headClose, retestBar, entry, bullishPattern, headPrice, target, full, /*onClose*/ true);

        // Arm E: pivot SL + close trigger + target scaled to 2x stop distance
        double stopDist = Math.abs(entry - pivotStop);
        double scaledTarget = bullishPattern ? entry + 2.0 * stopDist : entry - 2.0 * stopDist;
        simulateTrade(pivotCloseScaledTarget, retestBar, entry, bullishPattern, pivotStop, scaledTarget, full, /*onClose*/ true);

        // Arms F and G: right-shoulder SL (the structurally correct invalidation level)
        simulateTrade(rsClose, retestBar, entry, bullishPattern, rsPrice, target, full, /*onClose*/ true);
        double rsStopDist = Math.abs(entry - rsPrice);
        double rsScaledTarget = bullishPattern ? entry + 2.0 * rsStopDist : entry - 2.0 * rsStopDist;
        simulateTrade(rsCloseScaledTarget, retestBar, entry, bullishPattern, rsPrice, rsScaledTarget, full, /*onClose*/ true);
    }

    private void simulateTrade(Arm arm, int entryBar, double entry, boolean longSide,
                               double stop, double target, BarSeries full, boolean onClose) {
        // Geometry sanity
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
            double cl = bar.getClosePrice().doubleValue();
            if (longSide) {
                // Stop check
                boolean stopHit = onClose ? (cl <= stop) : (lo <= stop);
                if (stopHit) {
                    exitBar = i;
                    exitPrice = onClose ? cl : stop;
                    reason = "STOP";
                    break;
                }
                // Target check — keep intrabar for target since it's a "we got there" event
                if (hi >= target) { exitBar = i; exitPrice = target; reason = "TARGET"; break; }
            } else {
                boolean stopHit = onClose ? (cl >= stop) : (hi >= stop);
                if (stopHit) {
                    exitBar = i;
                    exitPrice = onClose ? cl : stop;
                    reason = "STOP";
                    break;
                }
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

    private String truncate(String s, int max) { return s.length() <= max ? s : s.substring(0, max); }

    private static class Arm {
        final String name;
        int trades = 0, wins = 0, losses = 0;
        int targetExits = 0, stopExits = 0, timeoutExits = 0;
        int skippedBadGeom = 0;
        double pnlSum = 0;
        Arm(String name) { this.name = name; }
    }
}
