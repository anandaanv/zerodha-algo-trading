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
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.DecimalNum;

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

/**
 * Persist MACD + StochRSI Short Signal simulation to JSON for visualization.
 *
 * Hypothesis: MACD daily bear cross + StochRSI hitting 100 (within ~5 daily bars) →
 * scan for bearish pattern (HNS, DoubleTop, DescendingTriangle) →
 * short on pattern breakdown.
 *
 * Triggers are flagged when:
 * 1. MACD crosses bearish (macd[t] < signal[t] AND macd[t-1] >= signal[t-1])
 * 2. StochRSI >= 99 at any bar in [t-5, t+5]
 *
 * Entry: close below pattern's lower trendline within 15 bars after pattern end.
 * Stop: pattern HIGH + 0.5% buffer.
 * Target: 1.5× measured move down from neckline.
 * Time stop: 30 bars after entry.
 */
class PersistMacdStochRsiShortSimTest {

    private static final Path DATA_DIR = Paths.get("/tmp/daily-bars-2015-2022");
    private static final Path OUTPUT_DIR = Paths.get("/tmp/sim-results");
    private static final int MIN_BARS = 200;
    private static final int MAX_BARS_PATTERN_SCAN = 30;
    private static final int MAX_BARS_BREAKDOWN = 15;
    private static final int TIME_STOP_BARS = 30;
    private static final double BUFFER_SL_PCT = 0.5;
    private static final int STOCHRSI_WINDOW = 10;  // Relax from ±5 to ±10
    private static final double STOCHRSI_THRESHOLD = 95.0;  // Relax from 99 to 95

    // MACD params (standard)
    private static final int MACD_SHORT_PERIOD = 12;
    private static final int MACD_LONG_PERIOD = 26;
    private static final int MACD_SIGNAL_PERIOD = 9;

    // RSI for StochRSI
    private static final int RSI_PERIOD = 14;
    private static final int STOCH_PERIOD = 14;  // min/max window

    @Test
    void persistSimulationResults() throws IOException {
        Assumptions.assumeTrue(Files.exists(DATA_DIR), "Daily bars data missing");

        Files.createDirectories(OUTPUT_DIR);

        String runId = "cpzz-macd-stochrsi-short-" + Instant.now()
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

        List<TradeRecord> allTrades = new ArrayList<>();
        int totalWins = 0, totalLosses = 0;
        double totalPnl = 0;
        Set<String> processedSymbols = new HashSet<>();

        // Diagnostic counters
        AtomicInteger totalTriggers = new AtomicInteger(0);
        AtomicInteger patternsFound = new AtomicInteger(0);
        AtomicInteger tradesEntered = new AtomicInteger(0);
        AtomicInteger macdCrossesTotal = new AtomicInteger(0);

        try (var stream = Files.newDirectoryStream(DATA_DIR, "*.csv")) {
            for (Path csv : stream) {
                String sym = csv.getFileName().toString().replace(".csv", "");
                processedSymbols.add(sym);
                BarSeries series = loadDailyCSV(sym, csv);
                if (series.getBarCount() < MIN_BARS) continue;

                scanForMacdStochRsiTriggers(sym, series, allTrades, totalTriggers,
                        patternsFound, tradesEntered, macdCrossesTotal);
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
        root.put("strategy_name", "CPZZ + MACD bear cross + StochRSI sat + bearish pattern + short breakdown");
        root.put("timeframe", "OneDay");
        root.put("stocks_count", processedSymbols.size());
        root.put("total_trades", allTrades.size());
        root.put("wins", totalWins);
        root.put("losses", totalLosses);
        root.put("total_pnl_pct", totalPnl);
        root.put("win_rate_pct", allTrades.isEmpty() ? 0 : 100.0 * totalWins / allTrades.size());
        root.put("mean_pnl_pct", allTrades.isEmpty() ? 0 : totalPnl / allTrades.size());

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

            // Trigger metadata
            tradeNode.put("trigger_macd_cross_time", t.triggerMacdCrossTime.toString());
            tradeNode.put("trigger_stochrsi_sat_time", t.triggerStochRsiSatTime.toString());
            tradeNode.put("days_between_triggers", t.daysBetweenTriggers);
            tradeNode.put("pattern_detected_after_n_bars", t.patternDetectedAfterNBars);

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
        updateIndex(runId, processedSymbols.size(), allTrades.size(), totalWins, totalLosses,
                totalPnl, allTrades.isEmpty() ? 0 : totalPnl / allTrades.size(), mapper);

        // Log diagnostics
        System.out.println("MACD crosses found: " + macdCrossesTotal.get());
        System.out.println("Triggers found: " + totalTriggers.get());
        System.out.println("Patterns detected: " + patternsFound.get());
        System.out.println("Trades entered: " + tradesEntered.get());
        System.out.println("Persisted " + allTrades.size() + " trades to " + runFile);
    }

    private void scanForMacdStochRsiTriggers(String sym, BarSeries series,
                                             List<TradeRecord> allTrades,
                                             AtomicInteger totalTriggers,
                                             AtomicInteger patternsFound,
                                             AtomicInteger tradesEntered,
                                             AtomicInteger macdCrossesTotal) {
        // Compute indicators
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        RSIIndicator rsi = new RSIIndicator(closePrice, RSI_PERIOD);
        MACDIndicator macd = new MACDIndicator(closePrice, MACD_SHORT_PERIOD, MACD_LONG_PERIOD);
        SMAIndicator signal = new SMAIndicator(macd, MACD_SIGNAL_PERIOD);

        // Compute StochRSI manually
        double[] stochRsi = computeStochRsi(rsi, series.getBarCount());

        // Run CPZZ once on full series for initial pivots
        List<PivotPoint> fullPivots = runCpzz(series);

        // Track trigger bars to avoid duplicates within 20 bars
        Set<Integer> triggeredBars = new HashSet<>();

        // Scan for MACD bear cross + StochRSI confluence
        for (int t = MACD_LONG_PERIOD + MACD_SIGNAL_PERIOD; t < series.getBarCount(); t++) {
            // MACD bear cross: macd[t] < signal[t] AND macd[t-1] >= signal[t-1]
            double macdVal = macd.getValue(t).doubleValue();
            double signalVal = signal.getValue(t).doubleValue();
            double macdValPrev = macd.getValue(t - 1).doubleValue();
            double signalValPrev = signal.getValue(t - 1).doubleValue();

            boolean macdCrossBearish = macdVal < signalVal && macdValPrev >= signalValPrev;
            if (!macdCrossBearish) continue;
            macdCrossesTotal.incrementAndGet();

            // StochRSI >= threshold within [t-WINDOW, t+WINDOW]
            int start = Math.max(0, t - STOCHRSI_WINDOW);
            int end = Math.min(series.getBarCount() - 1, t + STOCHRSI_WINDOW);
            int satBar = -1;
            for (int i = start; i <= end; i++) {
                if (stochRsi[i] >= STOCHRSI_THRESHOLD) {
                    satBar = i;
                    break;
                }
            }
            if (satBar < 0) continue;

            // Check duplicate trigger within 20 bars
            boolean isDuplicate = false;
            for (int prev : triggeredBars) {
                if (Math.abs(t - prev) <= 20) {
                    isDuplicate = true;
                    break;
                }
            }
            if (isDuplicate) continue;

            triggeredBars.add(t);
            totalTriggers.incrementAndGet();

            Instant triggerMacdTime = series.getBar(t).getEndTime();
            Instant triggerStochTime = series.getBar(satBar).getEndTime();
            int daysBetween = satBar - t;

            // Scan for bearish pattern in next 30 bars
            int patternScanEnd = Math.min(t + MAX_BARS_PATTERN_SCAN, series.getBarCount() - 1);
            BarSeries scanSeries = sliceUpTo(series, patternScanEnd);
            List<PivotPoint> scanPivots = runCpzz(scanSeries);

            // Try HNS, DoubleTop, DescendingTriangle
            List<PatternWithMeta> patterns = new ArrayList<>();

            for (HnsPattern p : new HnsClassicDetector().findAll(scanSeries, scanPivots, MAX_BARS_PATTERN_SCAN)) {
                if (p.endBarIndex() > t) {
                    patterns.add(new PatternWithMeta(p, "HNS", p.pivots(), p.endBarIndex()));
                }
            }

            for (DoubleTopPattern p : new DoubleTopClassicDetector().findAll(scanSeries, scanPivots, MAX_BARS_PATTERN_SCAN)) {
                if (p.endBarIndex() > t) {
                    patterns.add(new PatternWithMeta(p, "DOUBLE_TOP", p.pivots(), p.endBarIndex()));
                }
            }

            for (TrianglePattern p : new TriangleClassicDetector().findAll(scanSeries, scanPivots, MAX_BARS_PATTERN_SCAN)) {
                if (p.kind() == TrianglePattern.TriangleKind.DESCENDING && p.endBarIndex() > t) {
                    patterns.add(new PatternWithMeta(p, "DESCENDING_TRIANGLE", p.pivots(), p.endBarIndex()));
                }
            }

            if (patterns.isEmpty()) continue;
            patternsFound.incrementAndGet();

            // For each pattern, try to find breakdown
            for (PatternWithMeta patMeta : patterns) {
                int patternEndBar = patMeta.endBarIndex;
                List<PivotPoint> patternPivots = patMeta.pivots;
                String patternType = patMeta.patternType;

                // Scan for breakdown within MAX_BARS_BREAKDOWN after pattern end
                int breakdownMaxBar = Math.min(patternEndBar + MAX_BARS_BREAKDOWN, series.getBarCount() - 1);
                int breakdownBar = findBearishBreakdown(series, patternPivots, patternEndBar, breakdownMaxBar);

                if (breakdownBar < 0) continue;

                // Simulate short trade
                simulateAndCollect(sym, series, patternType, patternPivots,
                        breakdownBar, triggerMacdTime, triggerStochTime, daysBetween,
                        patternEndBar - t, allTrades, tradesEntered);
            }
        }
    }

    private int findBearishBreakdown(BarSeries series, List<PivotPoint> patternPivots,
                                     int patternEndBar, int maxBar) {
        // For bearish pattern: find lowest trendline (neckline or lower support)
        // Return first bar where close breaks below it
        if (patternPivots.isEmpty()) return -1;

        // Assume B (pivot 1) is neckline for HNS and DT
        double neckline = patternPivots.size() >= 2 ? patternPivots.get(1).price() : patternPivots.get(0).price();
        for (PivotPoint p : patternPivots) {
            neckline = Math.min(neckline, p.price());
        }

        for (int i = patternEndBar + 1; i <= maxBar; i++) {
            if (series.getBar(i).getClosePrice().doubleValue() < neckline) {
                return i;
            }
        }
        return -1;
    }

    private void simulateAndCollect(String sym, BarSeries series, String patternType,
                                    List<PivotPoint> patternPivots, int entryBar,
                                    Instant triggerMacdTime, Instant triggerStochTime,
                                    int daysBetween, int patternDetectedAfterNBars,
                                    List<TradeRecord> allTrades,
                                    AtomicInteger tradesEntered) {
        if (entryBar + TIME_STOP_BARS >= series.getBarCount()) return;

        double entryPrice = series.getBar(entryBar).getClosePrice().doubleValue();

        // Stop: highest pivot + 0.5% buffer
        double patternHigh = patternPivots.stream()
                .mapToDouble(PivotPoint::price)
                .max()
                .orElse(entryPrice);
        double stop = patternHigh * (1.0 + BUFFER_SL_PCT / 100.0);

        // Target: measured move = (neckline - lowest) * 1.5
        double neckline = patternPivots.size() >= 2 ? patternPivots.get(1).price() : patternPivots.get(0).price();
        double lowest = patternPivots.stream()
                .mapToDouble(PivotPoint::price)
                .min()
                .orElse(neckline);
        double measuredMove = (neckline - lowest) * 1.5;
        double target = neckline - measuredMove;

        // Sanity check (short: stop > entry > target)
        if (stop <= entryPrice || target >= entryPrice) return;

        // Simulate exit
        int exitBar = -1;
        double exitPrice = entryPrice;
        String exitReason = "TIME_STOP";
        int maxBar = Math.min(entryBar + TIME_STOP_BARS, series.getBarCount() - 1);

        for (int i = entryBar + 1; i <= maxBar; i++) {
            Bar bar = series.getBar(i);
            double hi = bar.getHighPrice().doubleValue();
            double cl = bar.getClosePrice().doubleValue();

            // Short: stop is above, target is below
            if (cl >= stop) {
                exitBar = i;
                exitPrice = cl;
                exitReason = "STOP";
                break;
            }
            if (hi <= target) {  // close-to-target check via high
                exitBar = i;
                exitPrice = target;
                exitReason = "TARGET";
                break;
            }
        }

        if (exitBar < 0) {
            exitBar = maxBar;
            exitPrice = series.getBar(maxBar).getClosePrice().doubleValue();
        }

        double pnl = (entryPrice - exitPrice) / entryPrice * 100.0;
        boolean wasWinner = pnl > 0;
        int holdingBars = exitBar - entryBar;

        // Collect bars around for chart
        List<BarData> barsAround = new ArrayList<>();
        int startBar = Math.max(0, entryBar - 100);
        int endBar = Math.min(series.getEndIndex(), exitBar + 50);
        for (int i = startBar; i <= endBar; i++) {
            Bar bar = series.getBar(i);
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

        TradeRecord trade = new TradeRecord(
                sym, patternType, "SHORT",
                entryBar, series.getBar(entryBar).getEndTime(), entryPrice,
                stop, target,
                exitBar, series.getBar(exitBar).getEndTime(), exitPrice, exitReason,
                pnl, wasWinner, holdingBars,
                triggerMacdTime, triggerStochTime, daysBetween, patternDetectedAfterNBars,
                new ArrayList<>(patternPivots), barsAround
        );

        tradesEntered.incrementAndGet();
        allTrades.add(trade);
    }

    private double[] computeStochRsi(RSIIndicator rsi, int barCount) {
        double[] stochRsi = new double[barCount];

        for (int i = 0; i < barCount; i++) {
            if (i < STOCH_PERIOD) {
                stochRsi[i] = 0;
                continue;
            }

            double minRsi = Double.MAX_VALUE;
            double maxRsi = -Double.MAX_VALUE;

            for (int j = i - STOCH_PERIOD + 1; j <= i; j++) {
                double rsiVal = rsi.getValue(j).doubleValue();
                minRsi = Math.min(minRsi, rsiVal);
                maxRsi = Math.max(maxRsi, rsiVal);
            }

            double range = maxRsi - minRsi;
            double rsiVal = rsi.getValue(i).doubleValue();
            stochRsi[i] = (range > 0.0001) ? ((rsiVal - minRsi) / range) * 100.0 : 0.0;
        }

        return stochRsi;
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

    private BarSeries sliceUpTo(BarSeries full, int endIndex) {
        BarSeries window = new BaseBarSeriesBuilder().withName(full.getName()).build();
        for (int i = 0; i <= endIndex && i <= full.getEndIndex(); i++) {
            window.addBar(full.getBar(i));
        }
        return window;
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

    private BarSeries loadDailyCSV(String sym, Path csv) throws IOException {
        BarSeries series = new BaseBarSeriesBuilder().withName(sym).build();
        List<String> lines = Files.readAllLines(csv);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split(",");
            if (parts.length < 6) continue;

            String ts = parts[0];
            if (!ts.endsWith("Z")) ts += "T00:00:00Z";
            series.addBar(BarsLoader.getBar(
                    Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]), Double.parseDouble(parts[4]),
                    Double.parseDouble(parts[5]), Instant.parse(ts), Duration.ofDays(1)));
        }
        return series;
    }

    private void updateIndex(String runId, int stocksCount, int tradeCount, int wins, int losses,
                            double totalPnl, double meanPnl, ObjectMapper mapper) throws IOException {
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
        runEntry.put("mean_pnl_pct", meanPnl);
        runEntry.put("win_rate_pct", tradeCount > 0 ? 100.0 * wins / tradeCount : 0);
        runEntry.put("created_at", Instant.now().toString());

        ((ArrayNode) indexRoot.get("runs")).add(runEntry);

        String jsonStr = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(indexRoot);
        Files.writeString(indexFile, jsonStr);
    }

    // Helper classes
    static class TradeRecord {
        String symbol, patternType, direction;
        int entryBar, exitBar, holdingBars, daysBetweenTriggers, patternDetectedAfterNBars;
        Instant entryTime, exitTime, triggerMacdCrossTime, triggerStochRsiSatTime;
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
                   Instant triggerMacdCrossTime, Instant triggerStochRsiSatTime,
                   int daysBetweenTriggers, int patternDetectedAfterNBars,
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
            this.triggerMacdCrossTime = triggerMacdCrossTime;
            this.triggerStochRsiSatTime = triggerStochRsiSatTime;
            this.daysBetweenTriggers = daysBetweenTriggers;
            this.patternDetectedAfterNBars = patternDetectedAfterNBars;
            this.patternPivots = patternPivots;
            this.barsAround = barsAround;
        }
    }

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

    static class PatternWithMeta {
        Object patternObj;
        String patternType;
        List<PivotPoint> pivots;
        int endBarIndex;

        PatternWithMeta(Object patternObj, String patternType, List<PivotPoint> pivots, int endBarIndex) {
            this.patternObj = patternObj;
            this.patternType = patternType;
            this.pivots = pivots;
            this.endBarIndex = endBarIndex;
        }
    }
}
