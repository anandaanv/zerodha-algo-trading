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
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persist DTB (Double Top / Double Bottom) retest-entry simulation trades to JSON for visualization.
 * Uses CPZZ pivots, neckline retest entry, 1-OTM SL, measured-move target,
 * close-basis stop trigger.
 */
class PersistDtbSimulationResultsTest {

    private static final Path DATA_DIR = Paths.get(
        System.getProperty("sim.hourly.dir", "/tmp/hourly-scan-bars"));
    private static final Path OUTPUT_DIR = Paths.get("/tmp/sim-results");
    private static final double RETEST_TOLERANCE_PCT = 0.5;
    private static final int MAX_BARS_TO_BREAKOUT = 30;
    private static final int MAX_BARS_TO_RETEST = 30;
    private static final int TIME_STOP_BARS = 30;
    private static final int WINDOW_SIZE = 200;
    private static final int SLIDE_STEP = 100;

    @Test
    void persistSimulationResults() throws IOException {
        Assumptions.assumeTrue(Files.exists(DATA_DIR), "Hourly scan data missing");

        // Create output directory
        Files.createDirectories(OUTPUT_DIR);

        // Generate run ID with timestamp
        String runId = "cpzz-dtb-retest-" + Instant.now()
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

        List<TradeRecord> allTrades = new ArrayList<>();
        int totalWins = 0, totalLosses = 0;
        double totalPnl = 0;
        Set<String> processedSymbols = new HashSet<>();

        // Diagnostic counters
        AtomicInteger totalDetected = new AtomicInteger(0);
        AtomicInteger noBreakout = new AtomicInteger(0);
        AtomicInteger noRetest = new AtomicInteger(0);
        AtomicInteger lateDetection = new AtomicInteger(0);
        AtomicInteger badGeometry = new AtomicInteger(0);
        AtomicInteger entered = new AtomicInteger(0);

        try (var stream = Files.newDirectoryStream(DATA_DIR, "*.csv")) {
            for (Path csv : stream) {
                String sym = csv.getFileName().toString().replace(".csv", "");
                processedSymbols.add(sym);
                BarSeries series = loadCsv(sym, csv);
                if (series.getBarCount() < WINDOW_SIZE + 50) continue;

                scanWithSlidingWindow(sym, series, allTrades, totalDetected, noBreakout, noRetest, lateDetection, badGeometry, entered);
            }
        }

        // Calculate stats
        for (TradeRecord t : allTrades) {
            if (t.wasWinner) totalWins++; else totalLosses++;
            totalPnl += t.pnlPct;
        }

        // Build JSON
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("run_id", runId);
        root.put("strategy_name", "CPZZ + DTB retest + 1-OTM SL + close trigger");
        root.put("timeframe", "OneHour");
        root.put("stocks_count", processedSymbols.size());
        root.put("total_trades", allTrades.size());
        root.put("wins", totalWins);
        root.put("losses", totalLosses);
        root.put("total_pnl_pct", totalPnl);

        // Add trades array
        ArrayNode tradesArray = mapper.createArrayNode();
        for (TradeRecord t : allTrades) {
            ObjectNode tradeNode = mapper.createObjectNode();
            tradeNode.put("symbol", t.symbol);
            tradeNode.put("pattern_type", t.patternType);
            tradeNode.put("direction", t.direction);
            tradeNode.put("entry_bar", t.entryBar);
            tradeNode.put("entry_time", t.entryTime.toString());
            tradeNode.put("entry_price", t.entryPrice);
            tradeNode.put("stop_initial", t.stopInitial);
            tradeNode.put("target_initial", t.targetInitial);
            tradeNode.put("exit_bar", t.exitBar);
            tradeNode.put("exit_time", t.exitTime.toString());
            tradeNode.put("exit_price", t.exitPrice);
            tradeNode.put("exit_reason", t.exitReason);
            tradeNode.put("pnl_pct", t.pnlPct);
            tradeNode.put("was_winner", t.wasWinner);
            tradeNode.put("holding_bars", t.holdingBars);

            // Pattern pivots
            ArrayNode pivotsArray = mapper.createArrayNode();
            for (int i = 0; i < t.patternPivots.size(); i++) {
                PivotPoint pp = t.patternPivots.get(i);
                ObjectNode pivotNode = mapper.createObjectNode();
                pivotNode.put("label", String.valueOf((char)('A' + i)));
                pivotNode.put("bar_index", pp.barIndex());
                pivotNode.put("timestamp", pp.time().toString());
                pivotNode.put("price", pp.price());
                pivotNode.put("type", pp.type().toString());
                pivotsArray.add(pivotNode);
            }
            tradeNode.set("pattern_pivots", pivotsArray);

            // Bars around
            ArrayNode barsArray = mapper.createArrayNode();
            for (BarData bd : t.barsAround) {
                ObjectNode barNode = mapper.createObjectNode();
                barNode.put("bar_index", bd.barIndex);
                barNode.put("timestamp", bd.timestamp.toString());
                barNode.put("open", bd.open);
                barNode.put("high", bd.high);
                barNode.put("low", bd.low);
                barNode.put("close", bd.close);
                barNode.put("volume", bd.volume);
                barsArray.add(barNode);
            }
            tradeNode.set("bars_around", barsArray);

            tradesArray.add(tradeNode);
        }
        root.set("trades", tradesArray);

        // Write run JSON
        Path runFile = OUTPUT_DIR.resolve(runId + ".json");
        String jsonStr = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        Files.writeString(runFile, jsonStr);

        // Update index
        updateIndex(runId, processedSymbols.size(), allTrades.size(), totalWins, totalLosses, totalPnl, mapper);

        // Log diagnostics
        System.out.println("Diagnostic: detected=" + totalDetected.get()
                + " noBreakout=" + noBreakout.get()
                + " noRetest=" + noRetest.get()
                + " lateDetection=" + lateDetection.get()
                + " badGeometry=" + badGeometry.get()
                + " entered=" + entered.get());
        System.out.println("Persisted " + allTrades.size() + " trades to " + runFile);
    }

    private void scanWithSlidingWindow(String sym, BarSeries full,
                                        List<TradeRecord> allTrades,
                                        AtomicInteger totalDetected,
                                        AtomicInteger noBreakout, AtomicInteger noRetest,
                                        AtomicInteger lateDetection, AtomicInteger badGeometry,
                                        AtomicInteger entered) {
        Set<String> seen = new HashSet<>();

        // Sliding window: extract pivots per window, detect patterns
        for (int windowEnd = WINDOW_SIZE; windowEnd <= full.getEndIndex(); windowEnd += SLIDE_STEP) {
            BarSeries window = sliceUpTo(full, windowEnd);
            List<PivotPoint> pivots = runCpzz(window);

            // Double Top patterns (bearish, SHORT direction)
            for (DoubleTopPattern p : new DoubleTopClassicDetector().findAll(window, pivots, WINDOW_SIZE)) {
                String key = sym + "-DT-" + p.endBarIndex();
                if (seen.contains(key)) continue;
                seen.add(key);
                totalDetected.incrementAndGet();
                simulateAndCollect(sym, p, pivots, full, false, allTrades, noBreakout, noRetest, lateDetection, badGeometry, entered);
            }

            // Double Bottom patterns (bullish, LONG direction)
            for (DoubleBottomPattern p : new DoubleBottomClassicDetector().findAll(window, pivots, WINDOW_SIZE)) {
                String key = sym + "-DB-" + p.endBarIndex();
                if (seen.contains(key)) continue;
                seen.add(key);
                totalDetected.incrementAndGet();
                simulateAndCollect(sym, p, pivots, full, true, allTrades, noBreakout, noRetest, lateDetection, badGeometry, entered);
            }
        }
    }

    private BarSeries sliceUpTo(BarSeries full, int endIndex) {
        BarSeries window = new BaseBarSeriesBuilder().withName(full.getName()).build();
        for (int i = 0; i <= endIndex && i <= full.getEndIndex(); i++) {
            window.addBar(full.getBar(i));
        }
        return window;
    }

    private void simulateAndCollect(String sym, Object patternObj, List<PivotPoint> pivots,
                                     BarSeries full, boolean bullishPattern,
                                     List<TradeRecord> allTrades,
                                     AtomicInteger noBreakout, AtomicInteger noRetest,
                                     AtomicInteger lateDetection, AtomicInteger badGeometry,
                                     AtomicInteger entered) {
        // Extract pivots based on pattern type
        List<PivotPoint> patternPivots;
        double atr;
        int eBar;

        if (patternObj instanceof DoubleTopPattern) {
            DoubleTopPattern p = (DoubleTopPattern) patternObj;
            patternPivots = p.pivots();
            atr = p.atr();
            eBar = p.endBarIndex();
        } else if (patternObj instanceof DoubleBottomPattern) {
            DoubleBottomPattern p = (DoubleBottomPattern) patternObj;
            patternPivots = p.pivots();
            atr = p.atr();
            eBar = p.endBarIndex();
        } else {
            return;
        }

        if (patternPivots.size() < 4) return;

        // Need room for breakout (30 bars) + retest (30 bars) + exit simulation (30 bars) + margin
        // With sliding window, eBar should be well before series end
        if (eBar + MAX_BARS_TO_BREAKOUT + MAX_BARS_TO_RETEST + TIME_STOP_BARS + 10 >= full.getBarCount()) {
            lateDetection.incrementAndGet();
            return;
        }

        double aPrice = patternPivots.get(0).price();
        double bPrice = patternPivots.get(1).price();  // neckline
        double cPrice = patternPivots.get(2).price();
        double dPrice = patternPivots.get(3).price();
        double neckline = bPrice;  // B IS the neckline for DTB
        double height = Math.abs(aPrice - neckline);

        // Breakout
        int breakoutBar = -1;
        int maxB = Math.min(eBar + MAX_BARS_TO_BREAKOUT, full.getEndIndex());
        for (int i = eBar + 1; i <= maxB; i++) {
            double c = full.getBar(i).getClosePrice().doubleValue();
            if ((bullishPattern && c > neckline) || (!bullishPattern && c < neckline)) {
                breakoutBar = i;
                break;
            }
        }
        if (breakoutBar < 0) {
            noBreakout.incrementAndGet();
            return;
        }

        // Retest
        int retestBar = -1;
        double tolerance = neckline * RETEST_TOLERANCE_PCT / 100.0;
        int maxR = Math.min(breakoutBar + MAX_BARS_TO_RETEST, full.getEndIndex());
        for (int i = breakoutBar + 1; i <= maxR; i++) {
            Bar bar = full.getBar(i);
            if (bar.getLowPrice().doubleValue() <= neckline + tolerance
                    && bar.getHighPrice().doubleValue() >= neckline - tolerance) {
                retestBar = i;
                break;
            }
        }
        if (retestBar < 0) {
            noRetest.incrementAndGet();
            return;
        }

        double entry = full.getBar(retestBar).getClosePrice().doubleValue();
        double target = bullishPattern ? neckline + height : neckline - height;

        // 1-OTM SL placement
        double stop;
        if (bullishPattern) {
            // LONG, DB: stop below min(A,C) by 1 ATR
            stop = Math.min(aPrice, cPrice) - atr;
        } else {
            // SHORT, DT: stop above max(A,C) by 1 ATR
            stop = Math.max(aPrice, cPrice) + atr;
        }

        // Geometry sanity
        if (bullishPattern && (stop >= entry || target <= entry)) {
            badGeometry.incrementAndGet();
            return;
        }
        if (!bullishPattern && (stop <= entry || target >= entry)) {
            badGeometry.incrementAndGet();
            return;
        }

        // Simulate trade with close-basis trigger
        int exitBar = -1;
        double exitPrice = entry;
        String exitReason = "TIMEOUT";
        int maxBar = Math.min(retestBar + TIME_STOP_BARS, full.getEndIndex());
        for (int i = retestBar + 1; i <= maxBar; i++) {
            Bar bar = full.getBar(i);
            double hi = bar.getHighPrice().doubleValue();
            double lo = bar.getLowPrice().doubleValue();
            double cl = bar.getClosePrice().doubleValue();
            if (bullishPattern) {
                // LONG: stop is below entry, target is above
                boolean stopHit = (cl <= stop);  // close-basis check
                if (stopHit) {
                    exitBar = i;
                    exitPrice = cl;
                    exitReason = "STOP";
                    break;
                }
                if (hi >= target) {
                    exitBar = i;
                    exitPrice = target;
                    exitReason = "TARGET";
                    break;
                }
            } else {
                // SHORT: stop is above entry, target is below
                boolean stopHit = (cl >= stop);  // close-basis check
                if (stopHit) {
                    exitBar = i;
                    exitPrice = cl;
                    exitReason = "STOP";
                    break;
                }
                if (lo <= target) {
                    exitBar = i;
                    exitPrice = target;
                    exitReason = "TARGET";
                    break;
                }
            }
        }
        if (exitBar < 0) {
            exitBar = maxBar;
            exitPrice = full.getBar(maxBar).getClosePrice().doubleValue();
        }

        double pnl = bullishPattern ? (exitPrice - entry) / entry * 100 : (entry - exitPrice) / entry * 100;
        boolean wasWinner = pnl > 0;
        int holdingBars = exitBar - retestBar;

        // Collect bars around entry/exit for chart rendering
        List<BarData> barsAround = new ArrayList<>();
        int startBar = Math.max(0, retestBar - 100);
        int endBar = Math.min(full.getEndIndex(), exitBar + 50);
        for (int i = startBar; i <= endBar; i++) {
            Bar bar = full.getBar(i);
            barsAround.add(new BarData(
                    i,
                    bar.getEndTime(),
                    bar.getOpenPrice().doubleValue(),
                    bar.getHighPrice().doubleValue(),
                    bar.getLowPrice().doubleValue(),
                    bar.getClosePrice().doubleValue(),
                    bar.getVolume().longValue()
            ));
        }

        // Create trade record
        String patternType = bullishPattern ? "DOUBLE_BOTTOM" : "DOUBLE_TOP";
        TradeRecord trade = new TradeRecord(
                sym,
                patternType,
                bullishPattern ? "LONG" : "SHORT",
                retestBar,
                full.getBar(retestBar).getEndTime(),
                entry,
                stop,
                target,
                exitBar,
                full.getBar(exitBar).getEndTime(),
                exitPrice,
                exitReason,
                pnl,
                wasWinner,
                holdingBars,
                new ArrayList<>(patternPivots),
                barsAround
        );

        entered.incrementAndGet();
        allTrades.add(trade);
    }

    private void updateIndex(String runId, int stocksCount, int tradeCount, int wins, int losses,
                            double totalPnl, ObjectMapper mapper) throws IOException {
        Path indexFile = OUTPUT_DIR.resolve("_index.json");
        ObjectNode indexRoot;

        if (Files.exists(indexFile)) {
            String content = Files.readString(indexFile);
            indexRoot = (ObjectNode) mapper.readTree(content);
        } else {
            indexRoot = mapper.createObjectNode();
            indexRoot.set("runs", mapper.createArrayNode());
        }

        ObjectNode runEntry = mapper.createObjectNode();
        runEntry.put("run_id", runId);
        runEntry.put("stocks_count", stocksCount);
        runEntry.put("total_trades", tradeCount);
        runEntry.put("wins", wins);
        runEntry.put("losses", losses);
        runEntry.put("total_pnl_pct", totalPnl);
        runEntry.put("win_rate_pct", tradeCount > 0 ? 100.0 * wins / tradeCount : 0);
        runEntry.put("created_at", Instant.now().toString());

        ((ArrayNode) indexRoot.get("runs")).add(runEntry);

        String jsonStr = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(indexRoot);
        Files.writeString(indexFile, jsonStr);
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
            if (cmp < 0) lo = mid + 1;
            else hi = mid - 1;
        }
        return -1;
    }

    private BarSeries loadCsv(String sym, Path csv) throws IOException {
        BarSeries series = new BaseBarSeriesBuilder().withName(sym).build();
        List<String> lines = Files.readAllLines(csv);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split(",");
            if (parts.length < 6) continue;
            String ts = parts[0];
            if (!ts.endsWith("Z")) ts += "Z";
            series.addBar(BarsLoader.getBar(
                    Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]), Double.parseDouble(parts[4]),
                    Double.parseDouble(parts[5]), Instant.parse(ts), Duration.ofHours(1)));
        }
        return series;
    }

    // Data class for trade record
    static class TradeRecord {
        String symbol, patternType, direction;
        int entryBar, exitBar, holdingBars;
        Instant entryTime, exitTime;
        double entryPrice, stopInitial, targetInitial, exitPrice, pnlPct;
        String exitReason;
        boolean wasWinner;
        List<PivotPoint> patternPivots;
        List<BarData> barsAround;

        TradeRecord(String symbol, String patternType, String direction,
                   int entryBar, Instant entryTime, double entryPrice,
                   double stopInitial, double targetInitial,
                   int exitBar, Instant exitTime, double exitPrice, String exitReason,
                   double pnlPct, boolean wasWinner, int holdingBars,
                   List<PivotPoint> patternPivots, List<BarData> barsAround) {
            this.symbol = symbol;
            this.patternType = patternType;
            this.direction = direction;
            this.entryBar = entryBar;
            this.entryTime = entryTime;
            this.entryPrice = entryPrice;
            this.stopInitial = stopInitial;
            this.targetInitial = targetInitial;
            this.exitBar = exitBar;
            this.exitTime = exitTime;
            this.exitPrice = exitPrice;
            this.exitReason = exitReason;
            this.pnlPct = pnlPct;
            this.wasWinner = wasWinner;
            this.holdingBars = holdingBars;
            this.patternPivots = patternPivots;
            this.barsAround = barsAround;
        }
    }

    // Data class for bar data
    static class BarData {
        int barIndex;
        Instant timestamp;
        double open, high, low, close;
        long volume;

        BarData(int barIndex, Instant timestamp, double open, double high,
               double low, double close, long volume) {
            this.barIndex = barIndex;
            this.timestamp = timestamp;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volume = volume;
        }
    }
}
