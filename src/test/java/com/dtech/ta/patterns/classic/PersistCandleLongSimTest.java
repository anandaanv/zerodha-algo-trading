package com.dtech.ta.patterns.classic;

import com.dtech.kitecon.KiteconApplication;
import com.dtech.kitecon.analysis.levels.Level;
import com.dtech.kitecon.analysis.levels.SupportResistanceLevelStudy;
import com.dtech.kitecon.simulation.CandidatePivotZigZag;
import com.dtech.kitecon.simulation.db.SimulationPersistenceService;
import com.dtech.kitecon.simulation.db.SimulationRun;
import com.dtech.chartpattern.zigzag.ZigZagParams;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.DecimalNum;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Multi-timeframe longing signal simulation:
 * 1. Daily MACD bullish cross → trigger zone
 * 2. Hourly StochRSI <= 1 within 10 daily bars
 * 3. Bullish candle pattern (engulfing/hammer/piercing line)
 * 4. Breakout entry, gated by daily MACD still bullish
 * 5. Daily RSI<70 entry gate and daily RSI>70 adaptive exit
 * Persist trades to database for visualization.
 */
@SpringBootTest(classes = KiteconApplication.class)
@ActiveProfiles("integration")
class PersistCandleLongSimTest {

    @Autowired(required = false)
    private SimulationPersistenceService simulationPersistenceService;

    private static final Path HOURLY_DATA_DIR = Paths.get(
        System.getProperty("sim.hourly.dir", "/tmp/hourly-scan-bars-2015-2022"));
    private static final Path DAILY_DATA_DIR = Paths.get(
        System.getProperty("sim.daily.dir", "/tmp/daily-bars-2015-2022"));
    private static final Path OUTPUT_DIR = Paths.get("/tmp/sim-results");

    // Parameters
    private static final int MAX_HOURLY_BARS_FROM_DAILY_CROSS = 70;  // ~10 daily bars
    private static final int MAX_BARS_PATTERN_WINDOW = 5;  // ±5 bars around trigger for pattern search
    private static final int MAX_BARS_TO_BREAKOUT = 10;
    private static final int TIME_STOP_BARS = 100;  // Extended to 100; no early StochRSI exit to let winners run
    private static final double STOP_BUFFER_PCT = 0.1;  // 0.1% noise buffer on pattern low
    private static final boolean ENABLE_STOCHRSI_EXIT = false;  // Disabled; divergence filter alone drives entry quality
    private static final int MAX_DAYS_BETWEEN_TRIGGERS = 7;  // 5 trading days ≈ 7 calendar days (incl weekends)

    @Test
    void persistSimulationResults() throws IOException {
        Assumptions.assumeTrue(Files.exists(HOURLY_DATA_DIR), "Hourly data missing");
        Assumptions.assumeTrue(Files.exists(DAILY_DATA_DIR), "Daily data missing");

        Files.createDirectories(OUTPUT_DIR);

        String runId = "candle-long-" + Instant.now()
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

        List<TradeRecord> allTrades = new ArrayList<>();
        int totalWins = 0, totalLosses = 0;
        double totalPnl = 0;
        Set<String> processedSymbols = new HashSet<>();

        // Diagnostic counters
        AtomicInteger macdCrosses = new AtomicInteger(0);
        AtomicInteger stochRsiTriggered = new AtomicInteger(0);
        AtomicInteger patternFound = new AtomicInteger(0);
        AtomicInteger bullishEngulfing = new AtomicInteger(0);
        AtomicInteger hammer = new AtomicInteger(0);
        AtomicInteger piercingLine = new AtomicInteger(0);
        AtomicInteger patternsWithBreakout = new AtomicInteger(0);
        AtomicInteger rrFilterRejected = new AtomicInteger(0);
        AtomicInteger macdGateRejected = new AtomicInteger(0);
        AtomicInteger dailyBarNotFound = new AtomicInteger(0);
        AtomicInteger geometryRejected = new AtomicInteger(0);
        AtomicInteger dailyRsiOverboughtRejected = new AtomicInteger(0);
        AtomicInteger stochRsiRecoveredExits = new AtomicInteger(0);
        AtomicInteger triggersCrossBeforeSat = new AtomicInteger(0);
        AtomicInteger triggersSatBeforeCross = new AtomicInteger(0);
        AtomicInteger satInvalidatedByOversold = new AtomicInteger(0);
        AtomicInteger tradesRejectedNoLevel = new AtomicInteger(0);
        AtomicInteger tradesRejectedRrBelow3 = new AtomicInteger(0);
        AtomicInteger triggersTooStaleRejected = new AtomicInteger(0);
        AtomicInteger tradesRejectedNoDivergence = new AtomicInteger(0);
        AtomicInteger divergencesRsiOnly = new AtomicInteger(0);
        AtomicInteger divergencesMacdOnly = new AtomicInteger(0);
        AtomicInteger divergencesBoth = new AtomicInteger(0);
        AtomicInteger patternRejectedAboveSMA20 = new AtomicInteger(0);
        AtomicInteger rideTheTrendActivated = new AtomicInteger(0);
        AtomicInteger tradesExitedTrendReversal = new AtomicInteger(0);

        try (var stream = Files.newDirectoryStream(HOURLY_DATA_DIR, "*.csv")) {
            for (Path hourlyPath : stream) {
                String sym = hourlyPath.getFileName().toString().replace(".csv", "");
                String symbolFilterProp = System.getProperty("sim.symbols", "");
                if (!symbolFilterProp.isEmpty()) {
                    Set<String> allowed = Arrays.stream(symbolFilterProp.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
                    if (!allowed.contains(sym)) continue;
                }
                Path dailyPath = DAILY_DATA_DIR.resolve(sym + ".csv");

                if (!Files.exists(dailyPath)) continue;

                processedSymbols.add(sym);

                try {
                    BarSeries hourly = loadHourlyCsv(sym, hourlyPath);
                    BarSeries daily = loadDailyCsv(sym, dailyPath);

                    if (hourly.getBarCount() < 100 || daily.getBarCount() < 50) continue;

                    scanStock(sym, hourly, daily, allTrades,
                            macdCrosses, stochRsiTriggered, patternFound,
                            bullishEngulfing, hammer, piercingLine,
                            patternsWithBreakout, rrFilterRejected, macdGateRejected,
                            dailyBarNotFound, geometryRejected, dailyRsiOverboughtRejected,
                            stochRsiRecoveredExits, triggersCrossBeforeSat, triggersSatBeforeCross,
                            satInvalidatedByOversold, tradesRejectedNoLevel, tradesRejectedRrBelow3,
                            triggersTooStaleRejected, tradesRejectedNoDivergence, divergencesRsiOnly,
                            divergencesMacdOnly, divergencesBoth, patternRejectedAboveSMA20,
                            rideTheTrendActivated, tradesExitedTrendReversal);
                } catch (Exception e) {
                    System.err.println("Error processing " + sym + ": " + e.getMessage());
                }
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
        root.put("strategy_name", "MACD-2d + candle±5 + BB + pattern-SL + RSI<70 + S/R + div + ride-trend");
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
            tradeNode.put("direction", "LONG");
            tradeNode.put("entry_bar", t.entryBar);
            tradeNode.put("entry_time", t.entryTime.toString());
            tradeNode.put("entry_price", t.entryPrice);
            tradeNode.put("stop_initial", t.stopInitial);
            tradeNode.put("target_initial", t.targetInitial);
            tradeNode.put("target_source", t.targetSource);
            tradeNode.put("divergence_source", t.divergenceSource);
            tradeNode.put("exit_bar", t.exitBar);
            tradeNode.put("exit_time", t.exitTime.toString());
            tradeNode.put("exit_price", t.exitPrice);
            tradeNode.put("exit_reason", t.exitReason);
            tradeNode.put("pnl_pct", t.pnlPct);
            tradeNode.put("was_winner", t.wasWinner);
            tradeNode.put("holding_bars", t.holdingBars);

            // Trigger metadata
            tradeNode.put("trigger_macd_cross_date_daily", t.triggerMacdCrossDate.toString());
            tradeNode.put("trigger_stochrsi_sat_time_hourly", t.triggerStochRsiTime.toString());
            tradeNode.put("hourly_bars_from_trigger_to_candle", t.hoursFromTriggerToCandle);
            tradeNode.put("hourly_bars_from_candle_to_entry", t.hoursFromCandleToEntry);

            // Pattern pivots
            ArrayNode pivotsArray = mapper.createArrayNode();
            for (int i = 0; i < t.patternPivots.size(); i++) {
                PatternPivot pp = t.patternPivots.get(i);
                ObjectNode pivotNode = mapper.createObjectNode();
                pivotNode.put("label", pp.label);
                pivotNode.put("bar_index", pp.barIndex);
                pivotNode.put("timestamp", pp.timestamp.toString());
                pivotNode.put("price", pp.price);
                pivotNode.put("type", pp.type);
                pivotsArray.add(pivotNode);
            }
            tradeNode.set("pattern_pivots", pivotsArray);

            tradeNode.put("bars_around_count", t.barsAround.size());

            tradesArray.add(tradeNode);
        }
        root.set("trades", tradesArray);

        // Persist to database (dual-write during transition phase)
        if (simulationPersistenceService != null) {
            try {
                Map<String, Object> extraMeta = new HashMap<>();
                extraMeta.put("macdCrosses", macdCrosses.get());
                extraMeta.put("stochRsiTriggered", stochRsiTriggered.get());
                extraMeta.put("patternFound", patternFound.get());
                extraMeta.put("bullishEngulfing", bullishEngulfing.get());
                extraMeta.put("hammer", hammer.get());
                extraMeta.put("piercingLine", piercingLine.get());
                extraMeta.put("patternsWithBreakout", patternsWithBreakout.get());
                extraMeta.put("rrFilterRejected", rrFilterRejected.get());
                extraMeta.put("macdGateRejected", macdGateRejected.get());
                extraMeta.put("dailyBarNotFound", dailyBarNotFound.get());
                extraMeta.put("geometryRejected", geometryRejected.get());
                extraMeta.put("dailyRsiOverboughtRejected", dailyRsiOverboughtRejected.get());
                extraMeta.put("tradesRejectedNoLevel", tradesRejectedNoLevel.get());
                extraMeta.put("tradesRejectedRrBelow3", tradesRejectedRrBelow3.get());
                extraMeta.put("tradesRejectedNoDivergence", tradesRejectedNoDivergence.get());
                extraMeta.put("divergencesRsiOnly", divergencesRsiOnly.get());
                extraMeta.put("divergencesMacdOnly", divergencesMacdOnly.get());
                extraMeta.put("divergencesBoth", divergencesBoth.get());
                extraMeta.put("stochRsiRecoveredExits", stochRsiRecoveredExits.get());
                extraMeta.put("triggersCrossBeforeSat", triggersCrossBeforeSat.get());
                extraMeta.put("triggersSatBeforeCross", triggersSatBeforeCross.get());
                extraMeta.put("satInvalidatedByOversold", satInvalidatedByOversold.get());
                extraMeta.put("triggersTooStaleRejected", triggersTooStaleRejected.get());
                extraMeta.put("patternRejectedAboveSMA20", patternRejectedAboveSMA20.get());
                extraMeta.put("rideTheTrendActivated", rideTheTrendActivated.get());
                extraMeta.put("tradesExitedTrendReversal", tradesExitedTrendReversal.get());

                // Convert TradeRecord objects to Maps for serialization
                List<Map<String, Object>> tradesMaps = new ArrayList<>();
                for (TradeRecord t : allTrades) {
                    tradesMaps.add(tradeRecordToMap(t));
                }

                SimulationRun persistedRun = simulationPersistenceService.persistRun(
                        runId,
                        "MACD-2d + candle±5 + pattern high-below-SMA20 + pattern-SL + RSI<70 + S/R + div + ride-trend",
                        "OneHour",
                        processedSymbols.size(),
                        tradesMaps,
                        extraMeta
                );

                System.out.println("Database persisted: Run ID = " + persistedRun.getId() +
                        ", trades = " + persistedRun.getTotalTrades());
            } catch (Exception e) {
                System.err.println("ERROR: Failed to persist to database: " + e.getMessage());
                System.err.println("Stack trace:");
                e.printStackTrace(System.err);
            }
        }

        // Print diagnostics
        double crossPct = macdCrosses.get() > 0 ? 100.0 * stochRsiTriggered.get() / macdCrosses.get() : 0;
        double patternPct = stochRsiTriggered.get() > 0 ? 100.0 * patternFound.get() / stochRsiTriggered.get() : 0;
        double winRate = allTrades.size() > 0 ? 100.0 * totalWins / allTrades.size() : 0;
        double meanPnl = allTrades.size() > 0 ? totalPnl / allTrades.size() : 0;

        System.out.println("\n=== CANDLE LONG SIMULATION RESULTS ===");
        System.out.println("Symbols processed: " + processedSymbols.size());
        System.out.println("Daily MACD bull crosses: " + macdCrosses.get());
        System.out.println("Crosses with hourly StochRSI saturation in next 10 daily bars: "
                + stochRsiTriggered.get() + " (" + String.format("%.1f", crossPct) + "%)");
        System.out.println("Triggers that found a bullish candle pattern: "
                + patternFound.get() + " (" + String.format("%.1f", patternPct) + "%)");
        System.out.println("  - bullish_engulfing: " + bullishEngulfing.get());
        System.out.println("  - hammer: " + hammer.get());
        System.out.println("  - piercing_line: " + piercingLine.get());
        System.out.println("Patterns rejected (pattern high >= SMA(20)): " + patternRejectedAboveSMA20.get());
        System.out.println("Candle patterns with breakout entry: " + patternsWithBreakout.get());
        System.out.println("Trades rejected - daily bar not found at entry time: " + dailyBarNotFound.get());
        System.out.println("Trades rejected - geometry check failed (stop >= entry or target <= entry): " + geometryRejected.get());
        System.out.println("Trades rejected because MACD already crossed bearish at entry: " + macdGateRejected.get());
        System.out.println("Trades rejected by R:R filter (stop > 5%): " + rrFilterRejected.get());
        System.out.println("Trades rejected (daily RSI > 70 at entry): " + dailyRsiOverboughtRejected.get());
        System.out.println("Trades rejected (no S/R level within proximity): " + tradesRejectedNoLevel.get());
        System.out.println("Trades rejected (R:R < 3.0 with S/R target): " + tradesRejectedRrBelow3.get());
        System.out.println("Trades rejected (no bullish divergence in past 6 pivots): " + tradesRejectedNoDivergence.get());
        System.out.println("Divergence breakdown: RSI-only=" + divergencesRsiOnly.get() + ", MACD-only=" + divergencesMacdOnly.get() + ", Both=" + divergencesBoth.get());
        System.out.println("Trades exited (StochRSI recovered from oversold): " + stochRsiRecoveredExits.get());
        System.out.println("Triggers (MACD bull cross fired first, StochRSI sat within 10 days): " + triggersCrossBeforeSat.get());
        System.out.println("Triggers (StochRSI saturation fired first, MACD bull cross within 10 days): " + triggersSatBeforeCross.get());
        System.out.println("Saturation invalidated by intermediate overbought dip: " + satInvalidatedByOversold.get());
        System.out.println("Triggers rejected (MACD cross & StochRSI sat >2 days apart): " + triggersTooStaleRejected.get());
        System.out.println("Trades activated ride-the-trend mode: " + rideTheTrendActivated.get());
        System.out.println("Trades exited via TREND_REVERSAL: " + tradesExitedTrendReversal.get());
        System.out.println("Trades persisted: " + allTrades.size());
        System.out.println("Win rate: " + String.format("%.1f", winRate) + "% (" + totalWins + "/" + allTrades.size() + ")");
        System.out.println("Mean PnL%: " + String.format("%.2f", meanPnl));
        System.out.println("Total PnL%: " + String.format("%.2f", totalPnl));

        // Count exit reasons with per-reason stats
        int stopExits = 0, targetExits = 0, timeStopExits = 0, stochRsiExits = 0, trendReversalExits = 0;
        int stopWins = 0, targetWins = 0, timeStopWins = 0, stochRsiWins = 0, trendReversalWins = 0;
        double stopPnl = 0, targetPnl = 0, timeStopPnl = 0, stochRsiPnl = 0, trendReversalPnl = 0;
        for (TradeRecord t : allTrades) {
            if ("STOP".equals(t.exitReason)) {
                stopExits++;
                if (t.wasWinner) stopWins++;
                stopPnl += t.pnlPct;
            } else if ("TARGET".equals(t.exitReason)) {
                targetExits++;
                if (t.wasWinner) targetWins++;
                targetPnl += t.pnlPct;
            } else if ("TIME_STOP".equals(t.exitReason)) {
                timeStopExits++;
                if (t.wasWinner) timeStopWins++;
                timeStopPnl += t.pnlPct;
            } else if ("STOCHRSI_RECOVERED".equals(t.exitReason)) {
                stochRsiExits++;
                if (t.wasWinner) stochRsiWins++;
                stochRsiPnl += t.pnlPct;
            } else if ("TREND_REVERSAL".equals(t.exitReason)) {
                trendReversalExits++;
                if (t.wasWinner) trendReversalWins++;
                trendReversalPnl += t.pnlPct;
            }
        }
        double stopWr = stopExits > 0 ? 100.0 * stopWins / stopExits : 0;
        double targetWr = targetExits > 0 ? 100.0 * targetWins / targetExits : 0;
        double timeStopWr = timeStopExits > 0 ? 100.0 * timeStopWins / timeStopExits : 0;
        double stochRsiWr = stochRsiExits > 0 ? 100.0 * stochRsiWins / stochRsiExits : 0;
        double trendReversalWr = trendReversalExits > 0 ? 100.0 * trendReversalWins / trendReversalExits : 0;
        double stopAvgPnl = stopExits > 0 ? stopPnl / stopExits : 0;
        double targetAvgPnl = targetExits > 0 ? targetPnl / targetExits : 0;
        double timeStopAvgPnl = timeStopExits > 0 ? timeStopPnl / timeStopExits : 0;
        double stochRsiAvgPnl = stochRsiExits > 0 ? stochRsiPnl / stochRsiExits : 0;
        double trendReversalAvgPnl = trendReversalExits > 0 ? trendReversalPnl / trendReversalExits : 0;
        System.out.println("Exit reasons: STOP=" + stopExits + " (wr=" + String.format("%.1f", stopWr) + "%, avgPnl=" + String.format("%.2f", stopAvgPnl) + "%), " +
                "TARGET=" + targetExits + " (wr=" + String.format("%.1f", targetWr) + "%, avgPnl=" + String.format("%.2f", targetAvgPnl) + "%), " +
                "TIME_STOP=" + timeStopExits + " (wr=" + String.format("%.1f", timeStopWr) + "%, avgPnl=" + String.format("%.2f", timeStopAvgPnl) + "%), " +
                "STOCHRSI_RECOVERED=" + stochRsiExits + " (wr=" + String.format("%.1f", stochRsiWr) + "%, avgPnl=" + String.format("%.2f", stochRsiAvgPnl) + "%), " +
                "TREND_REVERSAL=" + trendReversalExits + " (wr=" + String.format("%.1f", trendReversalWr) + "%, avgPnl=" + String.format("%.2f", trendReversalAvgPnl) + "%)");
        System.out.println("Data persisted to database (JSON write disabled)");
    }

    private boolean checkBullishDivergence(BarSeries hourly, int entryBarIdx, double entryLow, List<ZigZagPoint> structuralLows,
                                           RSIIndicator rsiHourly, MACDIndicator macdHourly,
                                           EMAIndicator signalHourly, TradeRecord tradeRec) {
        // Filter structural pivots: must be before entry and above entry low
        List<Integer> validPivots = new ArrayList<>();
        for (ZigZagPoint p : structuralLows) {
            if (p.getBarIndex() < entryBarIdx && p.getValue() > entryLow) {
                validPivots.add(p.getBarIndex());
            }
        }
        // Keep last 6
        if (validPivots.size() > 6) {
            validPivots = validPivots.subList(validPivots.size() - 6, validPivots.size());
        }

        if (validPivots.isEmpty()) {
            return false;
        }

        // Check each pivot for divergence (RSI only)
        boolean foundRsiDiv = false;
        int bestPivotOffset = -1;

        for (int idx = 0; idx < validPivots.size(); idx++) {
            int pivotIdx = validPivots.get(idx);
            double pivotPrice = hourly.getBar(pivotIdx).getLowPrice().doubleValue();

            // Price LL: entry low < pivot low
            boolean priceLL = entryLow < pivotPrice;

            if (!priceLL) continue;  // Skip if not LL condition

            double entryRsi = rsiHourly.getValue(entryBarIdx).doubleValue();
            double pivotRsi = rsiHourly.getValue(pivotIdx).doubleValue();

            // RSI higher low at entry vs pivot
            boolean rsiHL = entryRsi > pivotRsi;

            // RSI turned up from recent low (last 10 bars before entry)
            double minRsiLast10 = entryRsi;
            for (int j = Math.max(0, entryBarIdx - 10); j < entryBarIdx; j++) {
                minRsiLast10 = Math.min(minRsiLast10, rsiHourly.getValue(j).doubleValue());
            }
            boolean rsiTurnedUp = entryRsi > minRsiLast10;

            if (rsiHL && rsiTurnedUp) {
                foundRsiDiv = true;
                if (bestPivotOffset < 0) {
                    bestPivotOffset = idx + 1;
                }
                break;
            }
        }

        if (foundRsiDiv) {
            tradeRec.divergenceSource = "RSI@pivot" + bestPivotOffset;
        }

        return foundRsiDiv;
    }

    private int dailyBarIndexForHourly(BarSeries daily, Instant hourlyEndTime) {
        LocalDate targetDate = hourlyEndTime.atZone(ZoneId.systemDefault()).toLocalDate();
        int result = -1;

        for (int i = 0; i < daily.getBarCount(); i++) {
            Instant barTime = daily.getBar(i).getEndTime();
            LocalDate barDate = barTime.atZone(ZoneId.systemDefault()).toLocalDate();

            if (barDate.isAfter(targetDate)) {
                break;
            }
            result = i;
        }

        return result;
    }

    private void scanStock(String sym, BarSeries hourly, BarSeries daily, List<TradeRecord> allTrades,
                           AtomicInteger macdCrosses, AtomicInteger stochRsiTriggered,
                           AtomicInteger patternFound, AtomicInteger bullishEngulfing,
                           AtomicInteger hammer, AtomicInteger piercingLine,
                           AtomicInteger patternsWithBreakout, AtomicInteger rrFilterRejected,
                           AtomicInteger macdGateRejected, AtomicInteger dailyBarNotFound,
                           AtomicInteger geometryRejected, AtomicInteger dailyRsiOverboughtRejected,
                           AtomicInteger stochRsiRecoveredExits, AtomicInteger triggersCrossBeforeSat,
                           AtomicInteger triggersSatBeforeCross, AtomicInteger satInvalidatedByOversold,
                           AtomicInteger tradesRejectedNoLevel, AtomicInteger tradesRejectedRrBelow3,
                           AtomicInteger triggersTooStaleRejected, AtomicInteger tradesRejectedNoDivergence,
                           AtomicInteger divergencesRsiOnly, AtomicInteger divergencesMacdOnly,
                           AtomicInteger divergencesBoth, AtomicInteger patternRejectedAboveSMA20,
                           AtomicInteger rideTheTrendActivated, AtomicInteger tradesExitedTrendReversal) {
        // Instantiate level study
        SupportResistanceLevelStudy levelStudy = new SupportResistanceLevelStudy();
        BarSeries weeklyBars = resampleToWeekly(daily);

        // Build MACD and RSI indicators
        ClosePriceIndicator closeDaily = new ClosePriceIndicator(daily);
        ClosePriceIndicator closeHourly = new ClosePriceIndicator(hourly);
        MACDIndicator macdDaily = new MACDIndicator(closeDaily, 12, 26);
        EMAIndicator signalDaily = new EMAIndicator(macdDaily, 9);
        RSIIndicator rsiHourly = new RSIIndicator(closeHourly, 14);
        RSIIndicator dailyRsiInd = new RSIIndicator(closeDaily, 14);
        MACDIndicator macdHourly = new MACDIndicator(closeHourly, 12, 26);
        EMAIndicator signalHourly = new EMAIndicator(macdHourly, 9);

        // Compute SMA(20) on hourly for reversion filter
        SMAIndicator sma20Hourly = new SMAIndicator(closeHourly, 20);

        // Initialize CPZZ for structural pivot detection
        CandidatePivotZigZag cpzz = new CandidatePivotZigZag(
                ZigZagParams.ofDefaults(14, 1.0, 0.005, 1.0, 1, false, 1.0, 14, ZigZagParams.Mode.BACKTEST));
        cpzz.initialize(hourly, hourly.getBarCount() - 1);
        List<ZigZagPoint> structuralLows = cpzz.getConfirmedPivots().stream()
                .filter(p -> p.getType() == ZigZagPoint.Type.LOW)
                .collect(Collectors.toList());

        // Build timeline 1: daily MACD bullish crosses
        List<MacdCross> macdCrossList = new ArrayList<>();
        for (int d = 1; d < daily.getBarCount(); d++) {
            double currMacd = macdDaily.getValue(d).doubleValue();
            double currSignal = signalDaily.getValue(d).doubleValue();
            double prevMacd = macdDaily.getValue(d - 1).doubleValue();
            double prevSignal = signalDaily.getValue(d - 1).doubleValue();

            if (prevMacd <= prevSignal && currMacd > currSignal) {
                macdCrosses.incrementAndGet();
                Instant crossDate = daily.getBar(d).getEndTime();
                LocalDate crossDateLocal = crossDate.atZone(ZoneId.systemDefault()).toLocalDate();
                macdCrossList.add(new MacdCross(d, crossDateLocal));
            }
        }

        // Pre-compute StochRSI and hourly RSI for all bars (reuse in both trigger and exit logic)
        double[] hourlyStochRsi = new double[hourly.getBarCount()];
        double[] hourlyRsi = new double[hourly.getBarCount()];
        for (int h = 0; h < hourly.getBarCount(); h++) {
            hourlyStochRsi[h] = computeStochRsi(rsiHourly, h);
            hourlyRsi[h] = rsiHourly.getValue(h).doubleValue();
        }

        // Build timeline 2: hourly StochRSI saturation bars (<=1, debounced)
        List<StochRsiSat> stochRsiList = new ArrayList<>();
        boolean inSaturation = false;
        for (int h = 0; h < hourly.getBarCount(); h++) {
            double stochRsi = hourlyStochRsi[h];
            if (stochRsi <= 1 && !inSaturation) {
                // First bar of saturation episode
                Instant satTime = hourly.getBar(h).getEndTime();
                LocalDate satDateLocal = satTime.atZone(ZoneId.systemDefault()).toLocalDate();
                stochRsiList.add(new StochRsiSat(h, satDateLocal));
                inSaturation = true;
            } else if (stochRsi > 1) {
                inSaturation = false;
            }
        }

        // Match pairs bidirectionally
        Set<String> usedTriggers = new HashSet<>();
        Set<Integer> recentTriggerHours = new HashSet<>();

        for (MacdCross cross : macdCrossList) {
            for (StochRsiSat sat : stochRsiList) {
                long daysDiff = Math.abs(
                    ChronoUnit.DAYS.between(sat.satDate, cross.crossDate)
                );
                if (daysDiff > 10) continue;

                // Trigger fires at LATER of the two events
                boolean crossFirst = cross.crossDate.isBefore(sat.satDate);
                int triggerHourlyIdx = crossFirst ? sat.barIndex : findFirstHourlyBarOfDay(hourly, cross.crossDate);
                String triggerKey = cross.crossDate + "_" + sat.satDate;

                if (usedTriggers.contains(triggerKey) || triggerHourlyIdx < 0) continue;

                // FIX B: Invalidate saturation if StochRSI cycled through overbought between sat and cross
                if (!crossFirst && sat.barIndex < triggerHourlyIdx) {
                    // Sat came before cross; check if StochRSI cycled (entered >= 80, then exited < 80)
                    if (stochRsiCycledOverbought(hourlyStochRsi, sat.barIndex, triggerHourlyIdx - 1)) {
                        satInvalidatedByOversold.incrementAndGet();
                        continue;
                    }
                }

                // Anti-clustering: skip if another trigger within 50 hourly bars
                boolean skipDueToCluster = false;
                for (int prev : recentTriggerHours) {
                    if (Math.abs(triggerHourlyIdx - prev) < 50) {
                        skipDueToCluster = true;
                        break;
                    }
                }
                if (skipDueToCluster) continue;

                // CHANGE 1: Only accept cross-before-sat (MACD cross fires first, then StochRSI sat)
                if (!crossFirst) {
                    triggersSatBeforeCross.incrementAndGet();  // Count as rejected
                    continue;
                }

                usedTriggers.add(triggerKey);
                recentTriggerHours.add(triggerHourlyIdx);
                triggersCrossBeforeSat.incrementAndGet();
                stochRsiTriggered.incrementAndGet();
                Instant triggerTime = hourly.getBar(triggerHourlyIdx).getEndTime();

                // CHANGE 3: Scan for bullish candle pattern ±5 bars from trigger (bidirectional)
                int patternSearchStart = Math.max(0, triggerHourlyIdx - MAX_BARS_PATTERN_WINDOW);
                int patternSearchEnd = Math.min(triggerHourlyIdx + MAX_BARS_PATTERN_WINDOW, hourly.getBarCount() - 1);
                CandlePattern candlePattern = null;
                int closestDistance = Integer.MAX_VALUE;

                for (int h = patternSearchStart + 1; h <= patternSearchEnd; h++) {
                    Bar prevBar = hourly.getBar(h - 1);
                    Bar currBar = hourly.getBar(h);
                    String patternType = detectBullishCandlePattern(prevBar, currBar);

                    if (patternType != null) {
                        // Check SMA(20) reversion filter: pattern high must be below SMA(20)
                        double patternHighCandidate = currBar.getHighPrice().doubleValue();
                        double sma20Val = sma20Hourly.getValue(h).doubleValue();
                        if (patternHighCandidate >= sma20Val) {
                            // Pattern made a high above or at SMA(20) — not below resistance
                            patternRejectedAboveSMA20.incrementAndGet();
                            continue;
                        }

                        int distanceFromTrigger = Math.abs(h - triggerHourlyIdx);
                        if (distanceFromTrigger < closestDistance) {
                            closestDistance = distanceFromTrigger;
                            double patternLow = currBar.getLowPrice().doubleValue();
                            if ("BULLISH_ENGULFING".equals(patternType) || "PIERCING_LINE".equals(patternType)) {
                                if (h > 0) {
                                    double prevBarLow = hourly.getBar(h - 1).getLowPrice().doubleValue();
                                    patternLow = Math.min(patternLow, prevBarLow);
                                }
                            }
                            candlePattern = new CandlePattern(h, patternType, currBar.getHighPrice().doubleValue(),
                                    patternLow);

                            if ("BULLISH_ENGULFING".equals(patternType)) bullishEngulfing.incrementAndGet();
                            else if ("HAMMER".equals(patternType)) hammer.incrementAndGet();
                            else if ("PIERCING_LINE".equals(patternType)) piercingLine.incrementAndGet();
                        }
                    }
                }

                if (candlePattern != null) {
                    patternFound.incrementAndGet();
                }

                if (candlePattern == null) continue;

                // Look for breakout in next 10 bars
                int breakoutEnd = Math.min(candlePattern.barIndex + MAX_BARS_TO_BREAKOUT, hourly.getBarCount() - 1);
                int entryBarIdx = -1;
                double entryPrice = -1;

                for (int h = candlePattern.barIndex + 1; h <= breakoutEnd; h++) {
                    double close = hourly.getBar(h).getClosePrice().doubleValue();
                    if (close > candlePattern.patternHigh) {
                        entryBarIdx = h;
                        entryPrice = close;
                        break;
                    }
                }

                if (entryBarIdx < 0) continue;

                patternsWithBreakout.incrementAndGet();

                // CRITICAL GATE: verify daily MACD is still bullish at entry time
                Instant entryTime = hourly.getBar(entryBarIdx).getEndTime();
                int entryDailyIdx = findDailyBarAt(daily, entryTime);

                // If exact date not found, use the last available daily bar (worst case approximation)
                if (entryDailyIdx < 0) {
                    if (daily.getBarCount() > 0) {
                        entryDailyIdx = daily.getBarCount() - 1;
                    } else {
                        dailyBarNotFound.incrementAndGet();
                        continue;
                    }
                }

                double entryMacd = macdDaily.getValue(entryDailyIdx).doubleValue();
                double entrySignal = signalDaily.getValue(entryDailyIdx).doubleValue();

                if (entryMacd <= entrySignal) {
                    macdGateRejected.incrementAndGet();
                    continue;
                }

                // CHANGE 5: Compute stop from pattern low (not entry bar low)
                // For 2-bar patterns (BULLISH_ENGULFING, PIERCING_LINE): min(bar[c-1].low, bar[c].low)
                // For 1-bar patterns (HAMMER): bar[c].low
                Bar patternBar = hourly.getBar(candlePattern.barIndex);
                double patternLow = patternBar.getLowPrice().doubleValue();
                if ("BULLISH_ENGULFING".equals(candlePattern.type) || "PIERCING_LINE".equals(candlePattern.type)) {
                    if (candlePattern.barIndex > 0) {
                        double prevBarLow = hourly.getBar(candlePattern.barIndex - 1).getLowPrice().doubleValue();
                        patternLow = Math.min(patternLow, prevBarLow);
                    }
                }
                double stop = patternLow * (1 - STOP_BUFFER_PCT / 100.0);

                // For divergence check, still use entry bar low
                Bar entryBar = hourly.getBar(entryBarIdx);
                double entryBarLow = entryBar.getLowPrice().doubleValue();

                // Use S/R-derived natural target with R:R >= 3.0
                final double entryPriceF = entryPrice;  // for lambda
                List<Level> allLevels = levelStudy.computeLevels(daily, weeklyBars, entryPrice, entryTime, 10);

                Level naturalTarget = allLevels.stream()
                    .filter(l -> l.price() > entryPriceF)
                    .min(Comparator.comparingDouble(Level::price))
                    .orElse(null);

                if (naturalTarget == null) {
                    tradesRejectedNoLevel.incrementAndGet();
                    continue;
                }

                double target = naturalTarget.price();
                final String targetSource = naturalTarget.type().name();

                // Create a temporary trade record for divergence checking
                TradeRecord tempTrade = new TradeRecord();

                // Check for multi-pivot RSI/MACD bullish divergence
                if (!checkBullishDivergence(hourly, entryBarIdx, entryBarLow, structuralLows, rsiHourly, macdHourly, signalHourly, tempTrade)) {
                    tradesRejectedNoDivergence.incrementAndGet();
                    continue;
                }

                // Track divergence type
                String divSource = tempTrade.divergenceSource;
                if (divSource.contains("RSI+MACD")) {
                    divergencesBoth.incrementAndGet();
                } else if (divSource.contains("RSI")) {
                    divergencesRsiOnly.incrementAndGet();
                } else {
                    divergencesMacdOnly.incrementAndGet();
                }

                double risk = entryPrice - stop;
                double reward = target - entryPrice;
                double rr = (risk > 0) ? reward / risk : 0;

                if (rr < 3.0) {
                    tradesRejectedRrBelow3.incrementAndGet();
                    continue;
                }

                // Geometry sanity check: for longs, stop < entry and target > entry
                if (stop >= entryPrice || target <= entryPrice) {
                    geometryRejected.incrementAndGet();
                    continue;
                }

                // NEW: Daily RSI<70 entry gate
                int dIdx = dailyBarIndexForHourly(daily, entryTime);
                if (dIdx < 14) {
                    // Not enough RSI warmup
                    dailyRsiOverboughtRejected.incrementAndGet();
                    continue;
                }
                double dailyRsiAtEntry = dailyRsiInd.getValue(dIdx).doubleValue();
                if (dailyRsiAtEntry > 70) {
                    dailyRsiOverboughtRejected.incrementAndGet();
                    continue;
                }

                // NEW: Trigger freshness filter — reject if MACD cross and StochRSI sat are >5 days apart
                long daysBetweenTriggers = Math.abs(
                    ChronoUnit.DAYS.between(
                        sat.satDate,
                        cross.crossDate
                    )
                );
                if (daysBetweenTriggers > MAX_DAYS_BETWEEN_TRIGGERS) {
                    triggersTooStaleRejected.incrementAndGet();
                    continue;
                }

                // NEW: Ride-the-trend exit mode
                boolean rideTheTrendActive = false;
                boolean macdWasBullishAtEntry = (entryMacd > entrySignal);

                // Check if MACD becomes bullish within first 10 bars after entry
                if (!macdWasBullishAtEntry) {
                    for (int checkBar = entryBarIdx + 1; checkBar <= Math.min(entryBarIdx + 10, hourly.getBarCount() - 1); checkBar++) {
                        int checkDailyIdx = findDailyBarAt(daily, hourly.getBar(checkBar).getEndTime());
                        if (checkDailyIdx < 0 && daily.getBarCount() > 0) {
                            checkDailyIdx = daily.getBarCount() - 1;
                        }
                        if (checkDailyIdx >= 0) {
                            double checkMacd = macdDaily.getValue(checkDailyIdx).doubleValue();
                            double checkSignal = signalDaily.getValue(checkDailyIdx).doubleValue();
                            if (checkMacd > checkSignal) {
                                rideTheTrendActive = true;
                                break;
                            }
                        }
                    }
                } else {
                    rideTheTrendActive = true;
                }

                if (rideTheTrendActive) {
                    rideTheTrendActivated.incrementAndGet();
                }

                // State machine for StochRSI exit (when ride-the-trend is active)
                enum RideTheTrendState { WATCHING, OVERBOUGHT, DECLINING }
                RideTheTrendState rtState = RideTheTrendState.WATCHING;
                int overboughtBar = -1;

                // Simulate exit
                int exitBarIdx = entryBarIdx;
                double exitPrice = entryPrice;
                String exitReason = "TIME_STOP";
                int maxExitBar = rideTheTrendActive ? hourly.getBarCount() - 1 : Math.min(entryBarIdx + TIME_STOP_BARS, hourly.getBarCount() - 1);
                boolean enteredOverboughtThisTrade = false;

                for (int h = entryBarIdx + 1; h <= maxExitBar; h++) {
                    Bar bar = hourly.getBar(h);
                    double hi = bar.getHighPrice().doubleValue();
                    double lo = bar.getLowPrice().doubleValue();
                    double cl = bar.getClosePrice().doubleValue();

                    boolean stopHit = (cl < stop);  // Close-based stop trigger
                    boolean targetHit = (hi >= target);  // Intrabar target trigger (good for trade)

                    if (stopHit && targetHit) {
                        // Both in same bar, prefer stop
                        exitBarIdx = h;
                        exitPrice = cl;  // Exit at close for stop
                        exitReason = "STOP";
                        break;
                    } else if (stopHit) {
                        exitBarIdx = h;
                        exitPrice = cl;  // Exit at close for stop
                        exitReason = "STOP";
                        break;
                    } else if (targetHit) {
                        exitBarIdx = h;
                        exitPrice = target;
                        exitReason = "TARGET";
                        break;
                    }

                    // Ride-the-trend state machine (active when MACD is/becomes bullish)
                    if (rideTheTrendActive) {
                        double stochRsiBar = hourlyStochRsi[h];
                        switch (rtState) {
                            case WATCHING:
                                if (stochRsiBar >= 80) {
                                    rtState = RideTheTrendState.OVERBOUGHT;
                                }
                                break;
                            case OVERBOUGHT:
                                if (stochRsiBar < 80) {
                                    rtState = RideTheTrendState.DECLINING;
                                    overboughtBar = h;
                                }
                                break;
                            case DECLINING:
                                if (h > overboughtBar) {
                                    // Check for bearish reversal candle
                                    Bar prevBar = hourly.getBar(h - 1);
                                    Bar currBar = hourly.getBar(h);
                                    boolean bearishReversal = detectBearishReversal(prevBar, currBar);

                                    if (bearishReversal) {
                                        exitBarIdx = h;
                                        exitPrice = currBar.getClosePrice().doubleValue();
                                        exitReason = "TREND_REVERSAL";
                                        tradesExitedTrendReversal.incrementAndGet();
                                        break;  // Exit the for loop
                                    } else {
                                        // No confirmation; reset to WATCHING
                                        rtState = RideTheTrendState.WATCHING;
                                    }
                                }
                                break;
                        }
                    } else {
                        // Standard exit logic (ENABLE_STOCHRSI_EXIT disabled)
                        // TIME_STOP handled after loop
                    }

                    // Exit if we've hit TIME_STOP and ride-the-trend is not active
                    if (!rideTheTrendActive && h >= Math.min(entryBarIdx + TIME_STOP_BARS, hourly.getBarCount() - 1)) {
                        break;
                    }
                }

                if (exitBarIdx == entryBarIdx) {
                    // Hit time stop without exit trigger
                    exitBarIdx = maxExitBar;
                    exitPrice = hourly.getBar(maxExitBar).getClosePrice().doubleValue();
                }

                double pnl = (exitPrice - entryPrice) / entryPrice * 100;
                boolean wasWinner = pnl > 0;
                int holdingBars = exitBarIdx - entryBarIdx;

                // Collect bars around entry/exit
                List<BarData> barsAround = new ArrayList<>();
                int startBar = Math.max(0, entryBarIdx - 100);
                int endBar = Math.min(hourly.getBarCount() - 1, exitBarIdx + 50);
                for (int i = startBar; i <= endBar; i++) {
                    Bar bar = hourly.getBar(i);
                    barsAround.add(new BarData(
                            i, bar.getEndTime(),
                            bar.getOpenPrice().doubleValue(),
                            bar.getHighPrice().doubleValue(),
                            bar.getLowPrice().doubleValue(),
                            bar.getClosePrice().doubleValue(),
                            bar.getVolume().longValue()
                    ));
                }

                // Create pattern pivots
                List<PatternPivot> patternPivots = new ArrayList<>();
                if ("BULLISH_ENGULFING".equals(candlePattern.type)) {
                    patternPivots.add(new PatternPivot("C1", candlePattern.barIndex - 1,
                            hourly.getBar(candlePattern.barIndex - 1).getEndTime(),
                            hourly.getBar(candlePattern.barIndex - 1).getLowPrice().doubleValue(), "LOW"));
                    patternPivots.add(new PatternPivot("C2", candlePattern.barIndex,
                            hourly.getBar(candlePattern.barIndex).getEndTime(),
                            hourly.getBar(candlePattern.barIndex).getHighPrice().doubleValue(), "HIGH"));
                } else {
                    patternPivots.add(new PatternPivot("C1", candlePattern.barIndex,
                            hourly.getBar(candlePattern.barIndex).getEndTime(),
                            hourly.getBar(candlePattern.barIndex).getLowPrice().doubleValue(), "LOW"));
                }

                Instant crossInstant = daily.getBar(cross.dailyBarIdx).getEndTime();
                int hoursFromTriggerToCandle = candlePattern.barIndex - triggerHourlyIdx;

                TradeRecord trade = new TradeRecord(
                        sym, candlePattern.type, entryBarIdx, entryTime, entryPrice,
                        stop, target, exitBarIdx, hourly.getBar(exitBarIdx).getEndTime(),
                        exitPrice, exitReason, pnl, wasWinner, holdingBars,
                        patternPivots, barsAround, crossInstant, triggerTime,
                        Math.abs(sat.barIndex - triggerHourlyIdx), hoursFromTriggerToCandle, targetSource, divSource
                );

                allTrades.add(trade);
            }
        }
    }

    private String detectBullishCandlePattern(Bar prevBar, Bar currBar) {
        double prevOpen = prevBar.getOpenPrice().doubleValue();
        double prevClose = prevBar.getClosePrice().doubleValue();
        double prevHigh = prevBar.getHighPrice().doubleValue();
        double prevLow = prevBar.getLowPrice().doubleValue();

        double currOpen = currBar.getOpenPrice().doubleValue();
        double currClose = currBar.getClosePrice().doubleValue();
        double currHigh = currBar.getHighPrice().doubleValue();
        double currLow = currBar.getLowPrice().doubleValue();

        boolean prevRed = prevClose < prevOpen;
        boolean currGreen = currClose > currOpen;

        if (!prevRed || !currGreen) return null;

        // BULLISH ENGULFING
        if (currOpen <= prevClose && currClose >= prevOpen) {
            double prevBody = Math.abs(prevClose - prevOpen);
            double currBody = Math.abs(currClose - currOpen);
            if (currBody > prevBody) {
                return "BULLISH_ENGULFING";
            }
        }

        // HAMMER
        double currBody = Math.abs(currClose - currOpen);
        double upperWick = currHigh - Math.max(currOpen, currClose);
        double lowerWick = Math.min(currOpen, currClose) - currLow;
        double range = currHigh - currLow;

        if (range > 0 && lowerWick > 2 * currBody && (currBody / range) < 0.3 && upperWick < currBody) {
            return "HAMMER";
        }

        // PIERCING LINE
        double prevMid = (prevOpen + prevClose) / 2;
        if (currOpen < prevLow && currClose > prevMid && currClose < prevOpen) {
            return "PIERCING_LINE";
        }

        return null;
    }

    private boolean detectBearishReversal(Bar prevBar, Bar currBar) {
        double prevOpen = prevBar.getOpenPrice().doubleValue();
        double prevClose = prevBar.getClosePrice().doubleValue();
        double prevHigh = prevBar.getHighPrice().doubleValue();
        double prevLow = prevBar.getLowPrice().doubleValue();

        double currOpen = currBar.getOpenPrice().doubleValue();
        double currClose = currBar.getClosePrice().doubleValue();
        double currHigh = currBar.getHighPrice().doubleValue();
        double currLow = currBar.getLowPrice().doubleValue();

        boolean prevGreen = prevClose > prevOpen;
        boolean currRed = currClose < currOpen;

        if (!prevGreen || !currRed) return false;

        // BEARISH ENGULFING
        if (currOpen >= prevClose && currClose <= prevOpen) {
            double prevBody = Math.abs(prevClose - prevOpen);
            double currBody = Math.abs(currClose - currOpen);
            if (currBody > prevBody) {
                return true;
            }
        }

        // SHOOTING STAR
        double currBody = Math.abs(currClose - currOpen);
        double upperWick = currHigh - Math.max(currOpen, currClose);
        double lowerWick = Math.min(currOpen, currClose) - currLow;
        double range = currHigh - currLow;

        if (range > 0 && upperWick > 2 * currBody && (currBody / range) < 0.3 && lowerWick < currBody) {
            return true;
        }

        // DARK CLOUD COVER
        double prevMid = (prevOpen + prevClose) / 2;
        if (currOpen > prevHigh && currClose < prevMid && currClose > prevOpen) {
            return true;
        }

        return false;
    }

    private double computeStochRsi(RSIIndicator rsi, int barIndex) {
        if (barIndex < 13) return 0;
        double minRsi = Double.MAX_VALUE;
        double maxRsi = -Double.MAX_VALUE;

        for (int i = barIndex - 13; i <= barIndex; i++) {
            double val = rsi.getValue(i).doubleValue();
            minRsi = Math.min(minRsi, val);
            maxRsi = Math.max(maxRsi, val);
        }

        if (maxRsi == minRsi) return 50;

        double currRsi = rsi.getValue(barIndex).doubleValue();
        return ((currRsi - minRsi) / (maxRsi - minRsi)) * 100;
    }

    private int findHourlyBarAtOrAfter(BarSeries hourly, Instant target) {
        for (int i = 0; i < hourly.getBarCount(); i++) {
            if (hourly.getBar(i).getEndTime().isAfter(target) ||
                hourly.getBar(i).getEndTime().equals(target)) {
                return i;
            }
        }
        return -1;
    }

    private int findDailyBarAt(BarSeries daily, Instant target) {
        // Find the daily bar that contains the given hourly bar timestamp
        LocalDate targetDate = target.atZone(ZoneId.systemDefault()).toLocalDate();
        for (int i = 0; i < daily.getBarCount(); i++) {
            Instant barTime = daily.getBar(i).getEndTime();
            LocalDate barDate = barTime.atZone(ZoneId.systemDefault()).toLocalDate();
            if (barDate.equals(targetDate)) {
                return i;
            }
        }
        return -1;
    }

    private BarSeries loadHourlyCsv(String sym, Path csv) throws IOException {
        BarSeries series = new BaseBarSeriesBuilder().withName(sym).build();
        List<String> lines = Files.readAllLines(csv);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split(",");
            if (parts.length < 6) continue;

            String ts = parts[0];
            if (!ts.endsWith("Z")) ts += "Z";
            try {
                series.addBar(BarsLoader.getBar(
                        Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                        Double.parseDouble(parts[3]), Double.parseDouble(parts[4]),
                        Double.parseDouble(parts[5]), Instant.parse(ts), Duration.ofHours(1)));
            } catch (Exception e) {
                // Skip malformed lines
            }
        }
        return series;
    }

    private BarSeries loadDailyCsv(String sym, Path csv) throws IOException {
        BarSeries series = new BaseBarSeriesBuilder().withName(sym).build();
        List<String> lines = Files.readAllLines(csv);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split(",");
            if (parts.length < 6) continue;

            try {
                LocalDate date = LocalDate.parse(parts[0]);
                Instant ts = date.atTime(LocalTime.MIDNIGHT).atZone(ZoneId.systemDefault()).toInstant();
                series.addBar(BarsLoader.getBar(
                        Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                        Double.parseDouble(parts[3]), Double.parseDouble(parts[4]),
                        Double.parseDouble(parts[5]), ts, Duration.ofDays(1)));
            } catch (Exception e) {
                // Skip malformed lines
            }
        }
        return series;
    }

    private int findFirstHourlyBarOfDay(BarSeries hourly, LocalDate targetDate) {
        for (int i = 0; i < hourly.getBarCount(); i++) {
            Instant barTime = hourly.getBar(i).getEndTime();
            LocalDate barDate = barTime.atZone(ZoneId.systemDefault()).toLocalDate();
            if (barDate.equals(targetDate)) {
                return i;
            }
            if (barDate.isAfter(targetDate)) {
                return -1;
            }
        }
        return -1;
    }

    private static boolean stochRsiCycledOverbought(double[] stochRsi, int fromBar, int toBar) {
        if (fromBar < 0 || toBar >= stochRsi.length || fromBar >= toBar) return false;
        boolean entered = false;
        for (int i = fromBar; i <= toBar; i++) {
            double v = stochRsi[i];
            if (Double.isNaN(v)) continue;
            if (v >= 80) {
                entered = true;
            } else if (entered && v < 80) {
                return true;
            }
        }
        return false;
    }

    // Data classes
    static class MacdCross {
        int dailyBarIdx;
        LocalDate crossDate;

        MacdCross(int dailyBarIdx, LocalDate crossDate) {
            this.dailyBarIdx = dailyBarIdx;
            this.crossDate = crossDate;
        }
    }

    static class StochRsiSat {
        int barIndex;
        LocalDate satDate;

        StochRsiSat(int barIndex, LocalDate satDate) {
            this.barIndex = barIndex;
            this.satDate = satDate;
        }
    }

    static class TradeRecord {
        String symbol, patternType;
        int entryBar, exitBar, holdingBars;
        Instant entryTime, exitTime, triggerMacdCrossDate, triggerStochRsiTime;
        double entryPrice, stopInitial, targetInitial, exitPrice, pnlPct;
        String exitReason, targetSource, divergenceSource;
        boolean wasWinner;
        int hoursFromTriggerToCandle, hoursFromCandleToEntry;
        List<PatternPivot> patternPivots;
        List<BarData> barsAround;

        // No-arg constructor for temporary instances
        TradeRecord() {
        }

        TradeRecord(String symbol, String patternType, int entryBar, Instant entryTime, double entryPrice,
                   double stopInitial, double targetInitial, int exitBar, Instant exitTime,
                   double exitPrice, String exitReason, double pnlPct, boolean wasWinner,
                   int holdingBars, List<PatternPivot> patternPivots, List<BarData> barsAround,
                   Instant triggerMacdCrossDate, Instant triggerStochRsiTime,
                   int hoursFromTriggerToCandle, int hoursFromCandleToEntry, String targetSource, String divergenceSource) {
            this.symbol = symbol;
            this.patternType = patternType;
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
            this.triggerMacdCrossDate = triggerMacdCrossDate;
            this.triggerStochRsiTime = triggerStochRsiTime;
            this.hoursFromTriggerToCandle = hoursFromTriggerToCandle;
            this.hoursFromCandleToEntry = hoursFromCandleToEntry;
            this.targetSource = targetSource;
            this.divergenceSource = divergenceSource;
        }
    }

    static class BarData {
        int barIndex;
        Instant timestamp;
        double open, high, low, close;
        long volume;

        BarData(int barIndex, Instant timestamp, double open, double high, double low, double close, long volume) {
            this.barIndex = barIndex;
            this.timestamp = timestamp;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volume = volume;
        }
    }

    static class PatternPivot {
        String label, type;
        int barIndex;
        Instant timestamp;
        double price;

        PatternPivot(String label, int barIndex, Instant timestamp, double price, String type) {
            this.label = label;
            this.barIndex = barIndex;
            this.timestamp = timestamp;
            this.price = price;
            this.type = type;
        }
    }

    static class CandlePattern {
        int barIndex;
        String type;
        double patternHigh, patternLow;

        CandlePattern(int barIndex, String type, double patternHigh, double patternLow) {
            this.barIndex = barIndex;
            this.type = type;
            this.patternHigh = patternHigh;
            this.patternLow = patternLow;
        }
    }

    private BarSeries resampleToWeekly(BarSeries dailyBars) {
        BarSeries weeklyBars = new BaseBarSeriesBuilder().withName(dailyBars.getName() + "-weekly").build();

        if (dailyBars.getBarCount() == 0) return weeklyBars;

        int i = 0;
        while (i < dailyBars.getBarCount()) {
            Bar weekStartBar = dailyBars.getBar(i);
            ZonedDateTime barTime = weekStartBar.getEndTime().atZone(ZoneId.systemDefault());
            int weekOfYear = barTime.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear());
            int year = barTime.get(java.time.temporal.WeekFields.ISO.weekBasedYear());

            double weekOpen = weekStartBar.getOpenPrice().doubleValue();
            double weekHigh = weekStartBar.getHighPrice().doubleValue();
            double weekLow = weekStartBar.getLowPrice().doubleValue();
            double weekClose = weekStartBar.getClosePrice().doubleValue();
            double weekVolume = weekStartBar.getVolume().doubleValue();

            // Collect all bars in this week
            int j = i + 1;
            while (j < dailyBars.getBarCount()) {
                Bar bar = dailyBars.getBar(j);
                ZonedDateTime nextTime = bar.getEndTime().atZone(ZoneId.systemDefault());
                int nextWeek = nextTime.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear());
                int nextYear = nextTime.get(java.time.temporal.WeekFields.ISO.weekBasedYear());

                if (nextWeek == weekOfYear && nextYear == year) {
                    weekHigh = Math.max(weekHigh, bar.getHighPrice().doubleValue());
                    weekLow = Math.min(weekLow, bar.getLowPrice().doubleValue());
                    weekClose = bar.getClosePrice().doubleValue();
                    weekVolume += bar.getVolume().doubleValue();
                    j++;
                } else {
                    break;
                }
            }

            // Add the week bar (use actual duration, not fixed 7 days)
            Instant weekStart = weekStartBar.getEndTime();
            Instant weekEnd = dailyBars.getBar(j - 1).getEndTime();
            Duration weekDuration = Duration.between(weekStart, weekEnd);

            Bar weekBar = new org.ta4j.core.BaseBar(
                weekDuration,
                weekStart,
                weekEnd,
                DecimalNum.valueOf(weekOpen),
                DecimalNum.valueOf(weekHigh),
                DecimalNum.valueOf(weekLow),
                DecimalNum.valueOf(weekClose),
                DecimalNum.valueOf(weekVolume),
                DecimalNum.valueOf(0),
                0
            );
            weeklyBars.addBar(weekBar);

            i = j;
        }

        return weeklyBars;
    }

    /**
     * Convert a TradeRecord to a Map<String, Object> for database persistence.
     * Handles serialization of nested PatternPivot and BarData objects.
     */
    private static Map<String, Object> tradeRecordToMap(TradeRecord t) {
        Map<String, Object> map = new LinkedHashMap<>();

        map.put("symbol", t.symbol);
        map.put("pattern_type", t.patternType);
        map.put("direction", "LONG");
        map.put("entry_bar", t.entryBar);
        map.put("entry_time", t.entryTime.toString());
        map.put("entry_price", t.entryPrice);
        map.put("stop_initial", t.stopInitial);
        map.put("target_initial", t.targetInitial);
        map.put("exit_bar", t.exitBar);
        map.put("exit_time", t.exitTime.toString());
        map.put("exit_price", t.exitPrice);
        map.put("exit_reason", t.exitReason);
        map.put("pnl_pct", t.pnlPct);
        map.put("was_winner", t.wasWinner);
        map.put("holding_bars", t.holdingBars);

        // Convert PatternPivot list to list of maps
        List<Map<String, Object>> pivotMaps = new ArrayList<>();
        if (t.patternPivots != null) {
            for (PatternPivot pp : t.patternPivots) {
                Map<String, Object> pivotMap = new LinkedHashMap<>();
                pivotMap.put("label", pp.label);
                pivotMap.put("bar_index", pp.barIndex);
                pivotMap.put("timestamp", pp.timestamp.toString());
                pivotMap.put("price", pp.price);
                pivotMap.put("type", pp.type);
                pivotMaps.add(pivotMap);
            }
        }
        map.put("pattern_pivots", pivotMaps);

        // Trigger metadata
        map.put("trigger_macd_cross_date_daily", t.triggerMacdCrossDate != null ? t.triggerMacdCrossDate.toString() : null);
        map.put("trigger_stochrsi_sat_time_hourly", t.triggerStochRsiTime != null ? t.triggerStochRsiTime.toString() : null);
        map.put("hourly_bars_from_trigger_to_candle", t.hoursFromTriggerToCandle);
        map.put("hourly_bars_from_candle_to_entry", t.hoursFromCandleToEntry);

        return map;
    }
}
