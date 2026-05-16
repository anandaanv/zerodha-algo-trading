package com.dtech.ta.patterns.classic;

import com.dtech.chartpattern.zigzag.ZigZagParams;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.kitecon.simulation.CandidatePivotZigZag;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests the "pattern failure" reverse-trade hypothesis on HNS / Reverse HNS.
 *
 * Premise: when a bearish pattern (HNS) FAILS to break down — instead closes
 * ABOVE the head — that failure often precedes a strong bullish move ("from
 * failed patterns come fast moves"). Conversely for Reverse HNS that fails up.
 *
 * Three-arm simulation, all on CPZZ pivots, same detected pattern universe:
 *
 *   Arm A — ORIGINAL retest entry (bet the pattern works)
 *     HNS: short at retest, stop=head, target=neckline-height
 *     REV_HNS: long at retest, stop=head, target=neckline+height
 *
 *   Arm B — INVERTED retest entry (bet the pattern fails at retest)
 *     HNS: long at retest, stop=neckline-height, target=head
 *     REV_HNS: short at retest, stop=neckline+height, target=head
 *
 *   Arm C — FAILURE-CONFIRMED reverse entry (wait for pattern invalidation)
 *     HNS: wait for close > head price within N bars of detection
 *          enter LONG at that close, stop=right shoulder, target=head + height
 *     REV_HNS: wait for close < head price (inverse head)
 *          enter SHORT, stop=right shoulder, target=head - height
 *
 * This tells us whether the trader's gut feel — "if a pattern fails to break
 * out, it should break out strongly in the opposite direction" — has merit.
 *
 * @see CpzzVsWilliamsSimulationTest — Arm A baseline (CPZZ, +42.70%)
 */
class HnsFailureReverseTradeTest {

    private static final Path DATA_DIR = Paths.get(
        System.getProperty("sim.hourly.dir", "/tmp/hourly-scan-bars"));
    private static final Path OUTPUT_DIR = Paths.get("/tmp/sim-results");
    private static final double RETEST_TOLERANCE_PCT = 0.5;
    private static final int MAX_BARS_TO_BREAKOUT = 30;
    private static final int MAX_BARS_TO_RETEST = 30;
    private static final int MAX_BARS_TO_FAILURE = 30;
    private static final int TIME_STOP_BARS = 30;

    @Test
    void compareOriginalInvertedAndFailureReverseTrades() throws IOException {
        Assumptions.assumeTrue(Files.exists(DATA_DIR), "Hourly scan data missing");

        Arm original = new Arm("A: Original (bet pattern works)");
        Arm inverted = new Arm("B: Inverted retest (bet retest fails)");
        Arm failure = new Arm("C: Failure-confirmed reverse (gut-feel test)");

        try (var stream = Files.newDirectoryStream(DATA_DIR, "*.csv")) {
            for (Path csv : stream) {
                String sym = csv.getFileName().toString().replace(".csv", "");
                BarSeries series = loadCsv(sym, csv);
                if (series.getBarCount() < 250) continue;

                List<PivotPoint> pivots = runCpzz(series);

                List<HnsPattern> hns = new HnsClassicDetector().findAll(series, pivots, series.getBarCount());
                List<ReverseHnsPattern> revHns = new ReverseHnsClassicDetector().findAll(series, pivots, series.getBarCount());

                for (HnsPattern p : hns) {
                    simulateAll(sym, "HNS", p, series, false, original, inverted, failure);
                }
                for (ReverseHnsPattern p : revHns) {
                    simulateAll(sym, "REV_HNS", p, series, true, original, inverted, failure);
                }
            }
        }

        print(original);
        print(inverted);
        print(failure);
        printComparison(original, inverted, failure);

        assertFalse(original.trades == 0 && inverted.trades == 0 && failure.trades == 0,
                "All three arms produced zero trades");

        // Write JSON output for each arm
        try {
            Files.createDirectories(OUTPUT_DIR);
            String timestamp = Instant.now()
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

            writeArmJson(original, "A", timestamp);
            writeArmJson(inverted, "B", timestamp);
            writeArmJson(failure, "C", timestamp);
        } catch (IOException e) {
            System.err.println("Failed to write JSON: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void simulateAll(String sym, String type, ClassicPattern p, BarSeries full,
                              boolean bullishPattern, Arm original, Arm inverted, Arm failure) {
        int eBar = p.pivots().get(4).barIndex();
        if (eBar + 5 >= full.getBarCount()) return;

        double rsPrice = p.pivots().get(4).price();
        double bPrice = p.pivots().get(1).price();
        double dPrice = p.pivots().get(3).price();
        double headPrice = p.pivots().get(2).price();
        double neckline = (bPrice + dPrice) / 2.0;
        double height = Math.abs(headPrice - neckline);

        // --- Find breakout in pattern's expected direction ---
        int breakoutBar = -1;
        int maxBreakout = Math.min(eBar + MAX_BARS_TO_BREAKOUT, full.getEndIndex());
        for (int i = eBar + 1; i <= maxBreakout; i++) {
            double c = full.getBar(i).getClosePrice().doubleValue();
            // bullishPattern (REV_HNS): expect close > neckline
            // !bullishPattern (HNS):    expect close < neckline
            if ((bullishPattern && c > neckline) || (!bullishPattern && c < neckline)) {
                breakoutBar = i; break;
            }
        }

        // --- Find retest after breakout ---
        int retestBar = -1;
        double tolerance = neckline * RETEST_TOLERANCE_PCT / 100.0;
        if (breakoutBar >= 0) {
            int maxRetest = Math.min(breakoutBar + MAX_BARS_TO_RETEST, full.getEndIndex());
            for (int i = breakoutBar + 1; i <= maxRetest; i++) {
                Bar bar = full.getBar(i);
                if (bar.getLowPrice().doubleValue() <= neckline + tolerance
                        && bar.getHighPrice().doubleValue() >= neckline - tolerance) {
                    retestBar = i; break;
                }
            }
        }

        // --- Arm A and Arm B both fire on the retest ---
        if (retestBar > 0) {
            double entry = full.getBar(retestBar).getClosePrice().doubleValue();
            // Arm A: pattern direction
            simulateTrade(original, sym, type, retestBar, entry,
                    bullishPattern,                            // bullishPattern->long, else short
                    /*stop*/ headPrice,
                    /*target*/ bullishPattern ? neckline + height : neckline - height,
                    full);
            // Arm B: inverted
            simulateTrade(inverted, sym, type, retestBar, entry,
                    !bullishPattern,                           // flipped
                    /*stop*/ bullishPattern ? neckline + height : neckline - height,
                    /*target*/ headPrice,
                    full);
        }

        // --- Arm C: failure-confirmed reverse — independent of retest path ---
        // For HNS (bearish): failure = close ABOVE head within MAX_BARS_TO_FAILURE of detection
        // For REV_HNS (bullish): failure = close BELOW head within same window
        int failureBar = -1;
        int maxFail = Math.min(eBar + MAX_BARS_TO_FAILURE, full.getEndIndex());
        for (int i = eBar + 1; i <= maxFail; i++) {
            double c = full.getBar(i).getClosePrice().doubleValue();
            if ((!bullishPattern && c > headPrice) || (bullishPattern && c < headPrice)) {
                failureBar = i; break;
            }
        }
        if (failureBar > 0) {
            double entry = full.getBar(failureBar).getClosePrice().doubleValue();
            // For HNS failure (bullish reverse): stop = right shoulder, target = headPrice + height
            // For REV_HNS failure (bearish reverse): stop = right shoulder, target = headPrice - height
            double stop = rsPrice;
            double target = !bullishPattern ? headPrice + height : headPrice - height;
            simulateTrade(failure, sym, type, failureBar, entry,
                    !bullishPattern,    // direction is REVERSE of original pattern bias
                    stop, target, full);
        }
    }

    private void simulateTrade(Arm arm, String sym, String type, int entryBar, double entry,
                               boolean longSide, double stop, double target, BarSeries full) {
        // Sanity: stop and target must be on correct sides
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
                if (lo <= stop)   { exitBar = i; exitPrice = stop;   reason = "STOP";   break; }
                if (hi >= target) { exitBar = i; exitPrice = target; reason = "TARGET"; break; }
            } else {
                if (hi >= stop)   { exitBar = i; exitPrice = stop;   reason = "STOP";   break; }
                if (lo <= target) { exitBar = i; exitPrice = target; reason = "TARGET"; break; }
            }
        }
        if (exitBar < 0) {
            exitBar = maxBar;
            exitPrice = full.getBar(maxBar).getClosePrice().doubleValue();
        }
        double pnlPct = longSide ? (exitPrice - entry) / entry * 100
                                 : (entry - exitPrice) / entry * 100;
        arm.trades++;
        if (pnlPct > 0) arm.wins++; else arm.losses++;
        arm.pnlSum += pnlPct;
        arm.holdingSum += (exitBar - entryBar);
        switch (reason) {
            case "TARGET" -> arm.targetExits++;
            case "STOP" -> arm.stopExits++;
            default -> arm.timeoutExits++;
        }

        // Build and collect trade record
        Instant entryInstant = full.getBar(entryBar).getEndTime();
        Instant exitInstant = full.getBar(exitBar).getEndTime();
        TradeRecord record = new TradeRecord(
            sym, type, longSide ? "LONG" : "SHORT",
            entryInstant, entry, stop, target,
            exitInstant, exitPrice, reason, pnlPct
        );
        arm.tradesList.add(record);
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
        System.out.printf("  Mean holding:       %.1f bars%n", (double) a.holdingSum / a.trades);
        if (a.skippedBadGeom > 0) {
            System.out.printf("  Skipped (bad geom): %d%n", a.skippedBadGeom);
        }
    }

    private void printComparison(Arm a, Arm b, Arm c) {
        System.out.println("\n========== H2H COMPARISON ==========");
        System.out.printf("  %-22s %10s %10s %10s%n", "metric", "Original", "Inverted", "Failure");
        System.out.printf("  %-22s %10d %10d %10d%n", "trades", a.trades, b.trades, c.trades);
        if (a.trades > 0 && b.trades > 0 && c.trades > 0) {
            System.out.printf("  %-22s %9.0f%% %9.0f%% %9.0f%%%n", "win rate",
                    100.0 * a.wins / a.trades, 100.0 * b.wins / b.trades, 100.0 * c.wins / c.trades);
            System.out.printf("  %-22s %+9.2f%% %+9.2f%% %+9.2f%%%n", "mean P/L per trade",
                    a.pnlSum / a.trades, b.pnlSum / b.trades, c.pnlSum / c.trades);
            System.out.printf("  %-22s %+9.2f%% %+9.2f%% %+9.2f%%%n", "total P/L",
                    a.pnlSum, b.pnlSum, c.pnlSum);
        }
    }

    private void writeArmJson(Arm arm, String armLabel, String timestamp) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();

        root.put("run_id", "cpzz-hns-arm-" + armLabel + "-" + timestamp);
        root.put("strategy_name", "HnS — Arm " + armLabel + ": " + arm.name);
        root.put("timeframe", "OneHour");
        root.put("stocks_count", 0);  // Not tracked in this test
        root.put("total_trades", arm.tradesList.size());
        root.put("wins", arm.wins);
        root.put("losses", arm.losses);
        root.put("total_pnl_pct", arm.pnlSum);

        ArrayNode tradesArray = mapper.createArrayNode();
        for (TradeRecord t : arm.tradesList) {
            ObjectNode tradeNode = mapper.createObjectNode();
            tradeNode.put("symbol", t.symbol);
            tradeNode.put("direction", t.direction);
            tradeNode.put("pattern_type", t.patternType);
            tradeNode.put("entry_time", t.entryTime.toString());
            tradeNode.put("entry_price", t.entryPrice);
            tradeNode.put("stop_initial", t.stopInitial);
            tradeNode.put("target_initial", t.targetInitial);
            tradeNode.put("exit_time", t.exitTime.toString());
            tradeNode.put("exit_price", t.exitPrice);
            tradeNode.put("exit_reason", t.exitReason);
            tradeNode.put("pnl_pct", t.pnlPct);
            tradesArray.add(tradeNode);
        }
        root.set("trades", tradesArray);

        Path outFile = OUTPUT_DIR.resolve("cpzz-hns-arm-" + armLabel + "-" + timestamp + ".json");
        String jsonStr = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        Files.writeString(outFile, jsonStr);
        System.out.println("Wrote " + arm.tradesList.size() + " trades to " + outFile);
    }

    private static class Arm {
        final String name;
        int trades = 0, wins = 0, losses = 0;
        int targetExits = 0, stopExits = 0, timeoutExits = 0;
        int skippedBadGeom = 0;
        double pnlSum = 0;
        int holdingSum = 0;
        List<TradeRecord> tradesList = new ArrayList<>();
        Arm(String name) { this.name = name; }
    }

    static class TradeRecord {
        String symbol, patternType, direction;
        Instant entryTime, exitTime;
        double entryPrice, stopInitial, targetInitial, exitPrice, pnlPct;
        String exitReason;

        TradeRecord(String symbol, String patternType, String direction,
                   Instant entryTime, double entryPrice, double stopInitial, double targetInitial,
                   Instant exitTime, double exitPrice, String exitReason, double pnlPct) {
            this.symbol = symbol;
            this.patternType = patternType;
            this.direction = direction;
            this.entryTime = entryTime;
            this.entryPrice = entryPrice;
            this.stopInitial = stopInitial;
            this.targetInitial = targetInitial;
            this.exitTime = exitTime;
            this.exitPrice = exitPrice;
            this.exitReason = exitReason;
            this.pnlPct = pnlPct;
        }
    }
}
