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
 * Persist MACD Daily + StochRSI Hourly Short Signal simulation to JSON.
 *
 * Multi-timeframe setup:
 * 1. Daily MACD bearish cross (HTF regime)
 * 2. Hourly StochRSI ≥ 99 within 10 daily bars after cross (LTF timing)
 * 3. CPZZ on hourly with patterns (HNS, DT, DescendingTriangle) detected after trigger
 * 4. Entry: breakdown of pattern neckline
 * 5. Stop: pattern HEAD pivot + 0.5% buffer (not highest bar — specific pivot)
 * 6. Target: 1.5× measured_move from neckline
 * 7. Exit: intrabar stop/target or 60-bar time stop
 *
 * Fixes from v1:
 * - Use DAILY for MACD calc (not hourly)
 * - Use HOURLY for StochRSI (not daily)
 * - Correct multi-timeframe window: 10 DAILY bars = ~70 hourly bars
 * - Stop uses HEAD pivot specifically, not "highest bar in lookback"
 * - R:R filter: skip if (entry - target) < 1.5 × (stop - entry)
 * - Intrabar logic: prefer STOP over TARGET if both hit same bar
 * - No duplicate triggers within 50 hourly bars
 */
class PersistMacdDailyStochRsiHourlyShortSimTest {

    private static final Path DAILY_DATA_DIR = Paths.get("/tmp/daily-bars-2015-2022");
    private static final Path HOURLY_DATA_DIR = Paths.get("/tmp/hourly-scan-bars-2015-2022");
    private static final Path OUTPUT_DIR = Paths.get("/tmp/sim-results");

    private static final int DAILY_MIN_BARS = 100;
    private static final int HOURLY_MIN_BARS = 500;
    private static final int DAILY_MACD_LOOKBACK = 10;  // 10 daily bars = ~70 hourly bars
    private static final int HOURLY_PATTERN_SCAN_BARS = 100;
    private static final int HOURLY_ENTRY_BREAKDOWN_BARS = 30;
    private static final int HOURLY_TIME_STOP_BARS = 60;
    private static final int HOURLY_DUPLICATE_TRIGGER_DISTANCE = 50;
    private static final double BUFFER_SL_PCT = 0.5;
    private static final double RR_RATIO_THRESHOLD = 1.5;

    // Daily MACD params
    private static final int MACD_SHORT_PERIOD = 12;
    private static final int MACD_LONG_PERIOD = 26;
    private static final int MACD_SIGNAL_PERIOD = 9;

    // Hourly RSI → StochRSI
    private static final int RSI_PERIOD = 14;
    private static final int STOCH_PERIOD = 14;  // min/max window
    private static final double STOCHRSI_THRESHOLD = 99.0;

    @Test
    void persistSimulationResults() throws IOException {
        Assumptions.assumeTrue(Files.exists(DAILY_DATA_DIR), "Daily bars data missing");
        Assumptions.assumeTrue(Files.exists(HOURLY_DATA_DIR), "Hourly bars data missing");

        Files.createDirectories(OUTPUT_DIR);

        String runId = "cpzz-macd-daily-stochrsi-hourly-short-" + Instant.now()
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

        List<TradeRecord> allTrades = new ArrayList<>();
        int totalWins = 0, totalLosses = 0;
        double totalPnl = 0;
        Set<String> processedSymbols = new HashSet<>();

        // Diagnostic counters
        AtomicInteger totalMacdCrosses = new AtomicInteger(0);
        AtomicInteger totalTriggersWithSat = new AtomicInteger(0);
        AtomicInteger totalPatternsFound = new AtomicInteger(0);
        AtomicInteger totalTradesEntered = new AtomicInteger(0);
        AtomicInteger totalTradesRejectedByRR = new AtomicInteger(0);

        try (var stream = Files.newDirectoryStream(DAILY_DATA_DIR, "*.csv")) {
            for (Path dailyCsv : stream) {
                String sym = dailyCsv.getFileName().toString().replace(".csv", "");
                Path hourlyCsv = HOURLY_DATA_DIR.resolve(sym + ".csv");

                if (!Files.exists(hourlyCsv)) continue;

                processedSymbols.add(sym);
                BarSeries dailySeries = loadBarCSV(sym, dailyCsv, Duration.ofDays(1));
                BarSeries hourlySeries = loadBarCSV(sym, hourlyCsv, Duration.ofHours(1));

                if (dailySeries.getBarCount() < DAILY_MIN_BARS ||
                    hourlySeries.getBarCount() < HOURLY_MIN_BARS) continue;

                scanForMacdDailyStochRsiHourlyTriggers(
                        sym, dailySeries, hourlySeries, allTrades,
                        totalMacdCrosses, totalTriggersWithSat, totalPatternsFound,
                        totalTradesEntered, totalTradesRejectedByRR);
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
        root.put("strategy_name", "CPZZ + Daily MACD bear-cross + Hourly StochRSI sat + bearish pattern on hourly + short breakdown");
        root.put("timeframe", "OneHour");
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
            tradeNode.put("trigger_macd_cross_date_daily", t.triggerMacdCrossDateDaily.toString());
            tradeNode.put("trigger_stochrsi_sat_time_hourly", t.triggerStochRsiSatTimeHourly.toString());
            tradeNode.put("daily_bars_between", t.dailyBarsBetween);
            tradeNode.put("hourly_bars_between_trigger_and_pattern", t.hourlyBarsBetweenTriggerAndPattern);

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
        System.out.println("\n=== DIAGNOSTIC COUNTERS ===");
        System.out.println("Total daily MACD bear-crosses found: " + totalMacdCrosses.get());
        System.out.println("Crosses that produced an hourly StochRSI sat trigger: " +
                totalTriggersWithSat.get() + " (" +
                (totalMacdCrosses.get() > 0 ? String.format("%.1f%%", 100.0 * totalTriggersWithSat.get() / totalMacdCrosses.get()) : "N/A") + ")");
        System.out.println("Triggers that produced a pattern within 100 hourly bars: " +
                totalPatternsFound.get() + " (" +
                (totalTriggersWithSat.get() > 0 ? String.format("%.1f%%", 100.0 * totalPatternsFound.get() / totalTriggersWithSat.get()) : "N/A") + ")");
        System.out.println("Patterns that found an entry breakdown: " +
                totalTradesEntered.get() + " (" +
                (totalPatternsFound.get() > 0 ? String.format("%.1f%%", 100.0 * totalTradesEntered.get() / totalPatternsFound.get()) : "N/A") + ")");
        System.out.println("Trades rejected by R:R filter: " + totalTradesRejectedByRR.get());
        System.out.println("Trades persisted: " + allTrades.size());
        System.out.println("Win rate: " + (allTrades.isEmpty() ? "N/A" : String.format("%.1f%%", 100.0 * totalWins / allTrades.size())));
        System.out.println("Mean PnL%: " + (allTrades.isEmpty() ? "N/A" : String.format("%.2f%%", totalPnl / allTrades.size())));
        System.out.println("Total PnL%: " + String.format("%.2f%%", totalPnl));
        System.out.println("Result JSON: " + runFile);
    }

    private void scanForMacdDailyStochRsiHourlyTriggers(
            String sym, BarSeries dailySeries, BarSeries hourlySeries,
            List<TradeRecord> allTrades,
            AtomicInteger totalMacdCrosses,
            AtomicInteger totalTriggersWithSat,
            AtomicInteger totalPatternsFound,
            AtomicInteger totalTradesEntered,
            AtomicInteger totalTradesRejectedByRR) {

        // Compute daily MACD
        ClosePriceIndicator dailyClose = new ClosePriceIndicator(dailySeries);
        MACDIndicator dailyMacd = new MACDIndicator(dailyClose, MACD_SHORT_PERIOD, MACD_LONG_PERIOD);
        SMAIndicator dailySignal = new SMAIndicator(dailyMacd, MACD_SIGNAL_PERIOD);

        // Compute hourly RSI and StochRSI
        ClosePriceIndicator hourlyClose = new ClosePriceIndicator(hourlySeries);
        RSIIndicator hourlyRsi = new RSIIndicator(hourlyClose, RSI_PERIOD);
        double[] hourlyStochRsi = computeStochRsi(hourlyRsi, hourlySeries.getBarCount());

        // Track hourly triggers to avoid duplicates within 50 bars
        Set<Integer> triggeredHourlyBars = new HashSet<>();

        // Scan daily for MACD bear crosses
        for (int d = MACD_LONG_PERIOD + MACD_SIGNAL_PERIOD; d < dailySeries.getBarCount(); d++) {
            double macdVal = dailyMacd.getValue(d).doubleValue();
            double signalVal = dailySignal.getValue(d).doubleValue();
            double macdValPrev = dailyMacd.getValue(d - 1).doubleValue();
            double signalValPrev = dailySignal.getValue(d - 1).doubleValue();

            // MACD bear cross: macd[t] < signal[t] AND macd[t-1] >= signal[t-1]
            boolean macdCrossBearish = macdVal < signalVal && macdValPrev >= signalValPrev;
            if (!macdCrossBearish) continue;

            totalMacdCrosses.incrementAndGet();

            Instant crossDateDaily = dailySeries.getBar(d).getEndTime();

            // Find corresponding hourly bars for this daily bar
            int hourlyStartIdx = findHourlyBarIndexByDate(hourlySeries, dailySeries.getBar(d).getBeginTime());
            if (hourlyStartIdx < 0) continue;

            // Forward window: 10 daily bars from cross date
            int dailyEndIdx = Math.min(d + DAILY_MACD_LOOKBACK, dailySeries.getBarCount() - 1);
            Instant windowEndTime = dailySeries.getBar(dailyEndIdx).getEndTime();
            int hourlyEndIdx = findHourlyBarIndexByDate(hourlySeries, windowEndTime);
            if (hourlyEndIdx < 0) hourlyEndIdx = hourlySeries.getBarCount() - 1;

            // Find first hourly bar where StochRSI >= 99 in that window
            int triggerHourlyBar = -1;
            for (int h = hourlyStartIdx; h <= hourlyEndIdx && h < hourlySeries.getBarCount(); h++) {
                if (hourlyStochRsi[h] >= STOCHRSI_THRESHOLD) {
                    triggerHourlyBar = h;
                    break;
                }
            }

            if (triggerHourlyBar < 0) continue;

            // Check duplicate: no trigger within last 50 hourly bars
            boolean isDuplicate = false;
            for (int prev : triggeredHourlyBars) {
                if (Math.abs(triggerHourlyBar - prev) <= HOURLY_DUPLICATE_TRIGGER_DISTANCE) {
                    isDuplicate = true;
                    break;
                }
            }
            if (isDuplicate) continue;

            triggeredHourlyBars.add(triggerHourlyBar);
            totalTriggersWithSat.incrementAndGet();

            Instant triggerStochTimeHourly = hourlySeries.getBar(triggerHourlyBar).getEndTime();
            int dailyBarsBetween = triggerHourlyBar - hourlyStartIdx;

            // Scan for patterns on hourly: up to 100 bars after trigger
            int patternScanEnd = Math.min(triggerHourlyBar + HOURLY_PATTERN_SCAN_BARS, hourlySeries.getBarCount() - 1);
            BarSeries hourlyScanSeries = sliceUpTo(hourlySeries, patternScanEnd);
            List<PivotPoint> scanPivots = runCpzz(hourlyScanSeries);

            // Find bearish patterns
            List<PatternWithMeta> patterns = new ArrayList<>();

            for (HnsPattern p : new HnsClassicDetector().findAll(hourlyScanSeries, scanPivots, patternScanEnd)) {
                if (p.endBarIndex() > triggerHourlyBar) {
                    patterns.add(new PatternWithMeta(p, "HNS", p.pivots(), p.endBarIndex()));
                }
            }

            for (DoubleTopPattern p : new DoubleTopClassicDetector().findAll(hourlyScanSeries, scanPivots, patternScanEnd)) {
                if (p.endBarIndex() > triggerHourlyBar) {
                    patterns.add(new PatternWithMeta(p, "DOUBLE_TOP", p.pivots(), p.endBarIndex()));
                }
            }

            for (TrianglePattern p : new TriangleClassicDetector().findAll(hourlyScanSeries, scanPivots, patternScanEnd)) {
                if (p.kind() == TrianglePattern.TriangleKind.DESCENDING && p.endBarIndex() > triggerHourlyBar) {
                    patterns.add(new PatternWithMeta(p, "DESCENDING_TRIANGLE", p.pivots(), p.endBarIndex()));
                }
            }

            if (patterns.isEmpty()) continue;
            totalPatternsFound.incrementAndGet();

            // For each pattern, try to find entry and simulate trade
            for (PatternWithMeta patMeta : patterns) {
                int patternEndBar = patMeta.endBarIndex;
                List<PivotPoint> patternPivots = patMeta.pivots;
                String patternType = patMeta.patternType;

                // Find breakdown: first hourly bar after pattern end where close < neckline
                int breakdownMaxBar = Math.min(patternEndBar + HOURLY_ENTRY_BREAKDOWN_BARS, hourlySeries.getBarCount() - 1);
                int entryBar = findBearishBreakdown(hourlySeries, patternPivots, patternEndBar, breakdownMaxBar);

                if (entryBar < 0) continue;

                // Attempt trade simulation with R:R filter
                simulateAndCollect(
                        sym, hourlySeries, patternType, patternPivots, entryBar,
                        crossDateDaily, triggerStochTimeHourly,
                        triggerHourlyBar - hourlyStartIdx, patternEndBar - triggerHourlyBar,
                        allTrades, totalTradesEntered, totalTradesRejectedByRR);
            }
        }
    }

    private int findBearishBreakdown(BarSeries series, List<PivotPoint> patternPivots,
                                     int patternEndBar, int maxBar) {
        if (patternPivots.isEmpty()) return -1;

        // Neckline: minimum price among all pivots
        double neckline = patternPivots.stream()
                .mapToDouble(PivotPoint::price)
                .min()
                .orElse(Double.MAX_VALUE);

        // Find first bar after pattern end where close < neckline
        for (int i = patternEndBar + 1; i <= maxBar; i++) {
            if (series.getBar(i).getClosePrice().doubleValue() < neckline) {
                return i;
            }
        }
        return -1;
    }

    private void simulateAndCollect(
            String sym, BarSeries series, String patternType,
            List<PivotPoint> patternPivots, int entryBar,
            Instant triggerMacdCrossDateDaily, Instant triggerStochRsiSatTimeHourly,
            int dailyBarsBetween, int hourlyBarsBetweenTriggerAndPattern,
            List<TradeRecord> allTrades,
            AtomicInteger tradesEntered,
            AtomicInteger tradesRejectedByRR) {

        if (entryBar + HOURLY_TIME_STOP_BARS >= series.getBarCount()) return;

        double entryPrice = series.getBar(entryBar).getClosePrice().doubleValue();

        // Stop: HEAD pivot (specific pattern point) + 0.5% buffer
        // For HNS: HEAD is pivot C (index 2)
        // For DT: HEAD is max(A_price, C_price) (indices 0, 2)
        // For DT: HEAD is max(A_price, C_price) (indices 0, 2)
        // For DescendingTriangle: HEAD is highest pivot among A, C, E (indices 0, 2, 4)
        double headPrice = getHeadPivotPrice(patternType, patternPivots);
        double stop = headPrice * (1.0 + BUFFER_SL_PCT / 100.0);

        // Target: 1.5× measured_move from neckline
        // measured_move = head_price - neckline_price
        double neckline = patternPivots.stream()
                .mapToDouble(PivotPoint::price)
                .min()
                .orElse(entryPrice);
        double measuredMove = (headPrice - neckline) * 1.5;
        double target = neckline - measuredMove;

        // R:R sanity filter: skip if (entry - target) < 1.5 × (stop - entry)
        // For short: stop > entry > target
        if (stop <= entryPrice || target >= entryPrice) return;

        double riskPoints = stop - entryPrice;
        double rewardPoints = entryPrice - target;
        if (rewardPoints < RR_RATIO_THRESHOLD * riskPoints) {
            tradesRejectedByRR.incrementAndGet();
            return;
        }

        // Simulate exit
        int exitBar = -1;
        double exitPrice = entryPrice;
        String exitReason = "TIME_STOP";
        int maxBar = Math.min(entryBar + HOURLY_TIME_STOP_BARS, series.getBarCount() - 1);

        for (int i = entryBar + 1; i <= maxBar; i++) {
            Bar bar = series.getBar(i);
            double hi = bar.getHighPrice().doubleValue();
            double lo = bar.getLowPrice().doubleValue();
            double cl = bar.getClosePrice().doubleValue();

            // Intrabar check: prefer STOP over TARGET if both hit
            if (hi >= stop) {
                // Stop hit
                exitBar = i;
                exitPrice = stop;
                exitReason = "STOP";
                break;
            } else if (lo <= target) {
                // Target hit
                exitBar = i;
                exitPrice = target;
                exitReason = "TARGET";
                break;
            }
        }

        if (exitBar < 0) {
            exitBar = maxBar;
            exitPrice = series.getBar(maxBar).getClosePrice().doubleValue();
            exitReason = "TIME_STOP";
        }

        double pnl = (entryPrice - exitPrice) / entryPrice * 100.0;
        boolean wasWinner = pnl > 0;
        int holdingBars = exitBar - entryBar;

        // Collect bars around for chart
        List<BarData> barsAround = new ArrayList<>();
        int startBar = Math.max(0, entryBar - 30);
        int endBar = Math.min(series.getEndIndex(), exitBar + 10);
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
                triggerMacdCrossDateDaily, triggerStochRsiSatTimeHourly,
                dailyBarsBetween, hourlyBarsBetweenTriggerAndPattern,
                new ArrayList<>(patternPivots), barsAround
        );

        tradesEntered.incrementAndGet();
        allTrades.add(trade);
    }

    private double getHeadPivotPrice(String patternType, List<PivotPoint> pivots) {
        if (pivots.isEmpty()) return 0;

        // HNS: HEAD is pivot C (index 2)
        if ("HNS".equals(patternType) && pivots.size() >= 3) {
            return pivots.get(2).price();
        }

        // DOUBLE_TOP: HEAD is max(A_price, C_price) (indices 0, 2)
        if ("DOUBLE_TOP".equals(patternType) && pivots.size() >= 3) {
            double a = pivots.get(0).price();
            double c = pivots.get(2).price();
            return Math.max(a, c);
        }

        // DESCENDING_TRIANGLE: HEAD is highest pivot (A, C, E typically)
        if ("DESCENDING_TRIANGLE".equals(patternType)) {
            return pivots.stream()
                    .mapToDouble(PivotPoint::price)
                    .max()
                    .orElse(0);
        }

        // Fallback: highest pivot
        return pivots.stream()
                .mapToDouble(PivotPoint::price)
                .max()
                .orElse(0);
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

    private int findHourlyBarIndexByDate(BarSeries series, Instant date) {
        // Find hourly bar that contains or is closest to the given date
        for (int i = series.getBeginIndex(); i <= series.getEndIndex(); i++) {
            Instant barTime = series.getBar(i).getEndTime();
            if (barTime.isAfter(date) || barTime.equals(date)) {
                return i;
            }
        }
        return -1;
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

    private BarSeries loadBarCSV(String sym, Path csv, Duration barDuration) throws IOException {
        BarSeries series = new BaseBarSeriesBuilder().withName(sym).build();
        List<String> lines = Files.readAllLines(csv);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split(",");
            if (parts.length < 6) continue;

            String ts = parts[0];
            if (!ts.contains("T")) {
                // Daily format: "2015-02-02" → "2015-02-02T00:00:00Z"
                ts += "T00:00:00Z";
            } else if (!ts.endsWith("Z")) {
                // Hourly format: add Z if missing
                ts += "Z";
            }

            try {
                series.addBar(BarsLoader.getBar(
                        Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                        Double.parseDouble(parts[3]), Double.parseDouble(parts[4]),
                        Double.parseDouble(parts[5]), Instant.parse(ts), barDuration));
            } catch (Exception e) {
                // Skip malformed lines
            }
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

    // ========== Helper Classes ==========

    static class TradeRecord {
        String symbol, patternType, direction;
        int entryBar, exitBar, holdingBars, dailyBarsBetween, hourlyBarsBetweenTriggerAndPattern;
        Instant entryTime, exitTime, triggerMacdCrossDateDaily, triggerStochRsiSatTimeHourly;
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
                   Instant triggerMacdCrossDateDaily, Instant triggerStochRsiSatTimeHourly,
                   int dailyBarsBetween, int hourlyBarsBetweenTriggerAndPattern,
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
            this.triggerMacdCrossDateDaily = triggerMacdCrossDateDaily;
            this.triggerStochRsiSatTimeHourly = triggerStochRsiSatTimeHourly;
            this.dailyBarsBetween = dailyBarsBetween;
            this.hourlyBarsBetweenTriggerAndPattern = hourlyBarsBetweenTriggerAndPattern;
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
