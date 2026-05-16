package com.dtech.kitecon.simulation.dtb;

import com.dtech.aitrader.data.AgentDecision;
import com.dtech.aitrader.model.PatternSignal;
import com.dtech.aitrader.service.PatternAgentService;
import com.dtech.chartpattern.zigzag.ZigZagParams;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.kitecon.simulation.CandidatePivotZigZag;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import com.dtech.ta.patterns.classic.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

/**
 * Runs DTB (Double Top / Double Bottom) simulation with PatternAgent AI gating.
 * Compares PnL with vs without AI filtering.
 *
 * Run with:
 * ./gradlew test --tests '*PersistDtbSimulationWithPatternAgentTest*' -Dsim.useAgent=true -Dsim.hourly.dir=/tmp/wipro-only
 */
@SpringBootTest
class PersistDtbSimulationWithPatternAgentTest {

    private static final Path DATA_DIR = Paths.get(
        System.getProperty("sim.hourly.dir", "/tmp/hourly-scan-bars"));
    private static final Path OUTPUT_DIR = Paths.get("/tmp/sim-results-with-agent");
    private static final double RETEST_TOLERANCE_PCT = 0.5;
    private static final int MAX_BARS_TO_BREAKOUT = 30;
    private static final int MAX_BARS_TO_RETEST = 30;
    private static final int TIME_STOP_BARS = 30;
    private static final int WINDOW_SIZE = 200;
    private static final int SLIDE_STEP = 100;

    private static final boolean USE_PATTERN_AGENT = Boolean.parseBoolean(
        System.getProperty("sim.useAgent", "false"));
    private static final Long ANAND_USER_ID = 1L;

    @Autowired(required = false)
    private PatternAgentService patternAgentService;

    @Test
    void persistSimulationWithPatternAgent() throws IOException {
        Assumptions.assumeTrue(Files.exists(DATA_DIR), "Hourly scan data missing");

        System.out.println("=== DTB Simulation with PatternAgent ===");
        System.out.println("USE_PATTERN_AGENT: " + USE_PATTERN_AGENT);
        System.out.println("DATA_DIR: " + DATA_DIR);
        System.out.println("PatternAgentService available: " + (patternAgentService != null));

        Files.createDirectories(OUTPUT_DIR);

        String runId = (USE_PATTERN_AGENT ? "dtb-ai-" : "dtb-baseline-") + Instant.now()
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

        List<TradeRecord> allTrades = new ArrayList<>();
        int totalWins = 0, totalLosses = 0;
        double totalPnl = 0;
        Set<String> processedSymbols = new HashSet<>();

        AtomicInteger totalDetected = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);
        AtomicInteger agentErrors = new AtomicInteger(0);

        try (var stream = Files.newDirectoryStream(DATA_DIR, "*.csv")) {
            for (Path csv : stream) {
                String sym = csv.getFileName().toString().replace(".csv", "");
                processedSymbols.add(sym);
                BarSeries series = loadCsv(sym, csv);
                if (series.getBarCount() < WINDOW_SIZE + 50) continue;

                scanWithSlidingWindow(sym, series, allTrades, totalDetected, rejected, agentErrors);
            }
        }

        // Calculate stats
        for (TradeRecord t : allTrades) {
            if (t.wasWinner) totalWins++; else totalLosses++;
            totalPnl += t.pnlPct;
        }

        System.out.println("\n=== Results ===");
        System.out.println("Stocks processed: " + processedSymbols.size());
        System.out.println("Total trades: " + allTrades.size());
        System.out.println("Wins: " + totalWins + ", Losses: " + totalLosses);
        System.out.println("Total PnL%: " + String.format("%.2f", totalPnl));
        System.out.println("Avg PnL% per trade: " + String.format("%.2f", allTrades.isEmpty() ? 0 : totalPnl / allTrades.size()));
        System.out.println("Detection attempts: " + totalDetected.get());
        if (USE_PATTERN_AGENT) {
            System.out.println("Agent rejections: " + rejected.get());
            System.out.println("Agent errors: " + agentErrors.get());
        }
    }

    private void scanWithSlidingWindow(String sym, BarSeries full,
                                        List<TradeRecord> allTrades,
                                        AtomicInteger totalDetected,
                                        AtomicInteger rejected,
                                        AtomicInteger agentErrors) {
        Set<String> seen = new HashSet<>();

        for (int windowEnd = WINDOW_SIZE; windowEnd <= full.getEndIndex(); windowEnd += SLIDE_STEP) {
            BarSeries window = sliceUpTo(full, windowEnd);
            List<PivotPoint> pivots = runCpzz(window);

            // Double Top patterns (SHORT)
            for (DoubleTopPattern p : new DoubleTopClassicDetector().findAll(window, pivots, WINDOW_SIZE)) {
                String key = sym + "-DT-" + p.endBarIndex();
                if (seen.contains(key)) continue;
                seen.add(key);
                totalDetected.incrementAndGet();

                if (USE_PATTERN_AGENT && patternAgentService != null) {
                    // Build PatternSignal and call agent
                    PatternSignal signal = buildPatternSignal(sym, p, "DOUBLE_TOP", "SHORT", full, p.endBarIndex());
                    boolean shouldTrade = evaluateWithAgent(signal, rejected, agentErrors);
                    if (!shouldTrade) continue;
                }

                simulateAndCollect(sym, p, pivots, full, false, allTrades);
            }

            // Double Bottom patterns (LONG)
            for (DoubleBottomPattern p : new DoubleBottomClassicDetector().findAll(window, pivots, WINDOW_SIZE)) {
                String key = sym + "-DB-" + p.endBarIndex();
                if (seen.contains(key)) continue;
                seen.add(key);
                totalDetected.incrementAndGet();

                if (USE_PATTERN_AGENT && patternAgentService != null) {
                    PatternSignal signal = buildPatternSignal(sym, p, "DOUBLE_BOTTOM", "LONG", full, p.endBarIndex());
                    boolean shouldTrade = evaluateWithAgent(signal, rejected, agentErrors);
                    if (!shouldTrade) continue;
                }

                simulateAndCollect(sym, p, pivots, full, true, allTrades);
            }
        }
    }

    /**
     * Build a PatternSignal from detected pattern.
     */
    private PatternSignal buildPatternSignal(String sym, Object patternObj, String patternType,
                                              String direction, BarSeries full, int patternEndBar) {
        List<PivotPoint> pivots;
        double atr;

        if (patternObj instanceof DoubleTopPattern) {
            DoubleTopPattern p = (DoubleTopPattern) patternObj;
            pivots = p.pivots();
            atr = p.atr();
        } else if (patternObj instanceof DoubleBottomPattern) {
            DoubleBottomPattern p = (DoubleBottomPattern) patternObj;
            pivots = p.pivots();
            atr = p.atr();
        } else {
            return null;
        }

        if (pivots.size() < 4) return null;

        double neckline = pivots.get(1).price();
        double breakoutPrice = "SHORT".equals(direction) ? neckline * 0.999 : neckline * 1.001;
        double measuredMove = atr * 1.5;
        double sl = "SHORT".equals(direction) ? neckline * 1.01 : neckline * 0.99;
        double target = "SHORT".equals(direction) ? breakoutPrice - measuredMove : breakoutPrice + measuredMove;

        Bar endBar = full.getBar(patternEndBar);
        Instant signalTime = endBar.getEndTime();

        Map<String, Object> context = new HashMap<>();
        context.put("pattern_pivots", pivots.stream()
            .map(p -> String.format("%.2f", p.price()))
            .toList());
        context.put("atr", atr);
        context.put("neckline", String.format("%.2f", neckline));

        return PatternSignal.builder()
            .symbol(sym)
            .patternType(patternType)
            .patternSource("DTB")
            .signalRef("dtb-sim-" + patternEndBar)
            .direction(direction)
            .suggestedEntry(breakoutPrice)
            .suggestedSl(sl)
            .suggestedTarget(target)
            .signalTime(signalTime)
            .timeframe("OneHour")
            .extraContext(context)
            .build();
    }

    /**
     * Evaluates a signal with PatternAgent, returns true if TRADE, false if NO_TRADE.
     */
    private boolean evaluateWithAgent(PatternSignal signal, AtomicInteger rejected, AtomicInteger agentErrors) {
        try {
            AgentDecision decision = patternAgentService.decide(signal, ANAND_USER_ID);
            if ("NO_TRADE".equals(decision.getVerdict())) {
                rejected.incrementAndGet();
                return false;
            }
            return true;
        } catch (Exception e) {
            agentErrors.incrementAndGet();
            System.err.println("Agent error for " + signal.getSymbol() + ": " + e.getMessage());
            // On error, skip this trade (conservative)
            return false;
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
                                     List<TradeRecord> allTrades) {
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

        if (eBar + MAX_BARS_TO_BREAKOUT + MAX_BARS_TO_RETEST + TIME_STOP_BARS + 10 >= full.getBarCount()) {
            return;
        }

        double aPrice = patternPivots.get(0).price();
        double bPrice = patternPivots.get(1).price();  // neckline
        double cPrice = patternPivots.get(2).price();
        double dPrice = patternPivots.get(3).price();

        // Simplified: assume entry at neckline retest
        int entryBar = findRetestBar(full, eBar, bPrice, RETEST_TOLERANCE_PCT, bullishPattern);
        if (entryBar < 0 || entryBar + TIME_STOP_BARS >= full.getBarCount()) return;

        double entryPrice = full.getBar(entryBar).getClosePrice().doubleValue();
        double slPrice = bullishPattern ? bPrice * 0.98 : bPrice * 1.02;
        double targetPrice = bullishPattern ? entryPrice + atr * 1.5 : entryPrice - atr * 1.5;

        // Find exit
        int exitBar = entryBar;
        double exitPrice = entryPrice;
        String exitReason = "TIME_STOP";

        for (int i = entryBar + 1; i < Math.min(entryBar + TIME_STOP_BARS, full.getBarCount()); i++) {
            double low = full.getBar(i).getLowPrice().doubleValue();
            double high = full.getBar(i).getHighPrice().doubleValue();
            double close = full.getBar(i).getClosePrice().doubleValue();

            if (bullishPattern) {
                if (low <= slPrice) {
                    exitBar = i;
                    exitPrice = slPrice;
                    exitReason = "STOP";
                    break;
                }
                if (high >= targetPrice) {
                    exitBar = i;
                    exitPrice = targetPrice;
                    exitReason = "TARGET";
                    break;
                }
            } else {
                if (high >= slPrice) {
                    exitBar = i;
                    exitPrice = slPrice;
                    exitReason = "STOP";
                    break;
                }
                if (low <= targetPrice) {
                    exitBar = i;
                    exitPrice = targetPrice;
                    exitReason = "TARGET";
                    break;
                }
            }
        }

        if (exitBar >= full.getBarCount()) return;

        double pnl = bullishPattern ? (exitPrice - entryPrice) : (entryPrice - exitPrice);
        double pnlPct = (pnl / entryPrice) * 100;

        TradeRecord trade = new TradeRecord();
        trade.symbol = sym;
        trade.patternType = bullishPattern ? "DOUBLE_BOTTOM" : "DOUBLE_TOP";
        trade.direction = bullishPattern ? "LONG" : "SHORT";
        trade.entryPrice = entryPrice;
        trade.entryBar = entryBar;
        trade.entryTime = full.getBar(entryBar).getEndTime();
        trade.exitPrice = exitPrice;
        trade.exitBar = exitBar;
        trade.exitTime = full.getBar(exitBar).getEndTime();
        trade.exitReason = exitReason;
        trade.stopInitial = slPrice;
        trade.targetInitial = targetPrice;
        trade.pnlPct = pnlPct;
        trade.wasWinner = pnlPct > 0;
        trade.holdingBars = exitBar - entryBar;
        trade.patternPivots = patternPivots;

        allTrades.add(trade);
    }

    private int findRetestBar(BarSeries series, int startBar, double neckline, double tolerancePct, boolean bullish) {
        for (int i = startBar + 1; i < Math.min(startBar + MAX_BARS_TO_RETEST, series.getBarCount()); i++) {
            double low = series.getBar(i).getLowPrice().doubleValue();
            double high = series.getBar(i).getHighPrice().doubleValue();
            double tolerance = neckline * (tolerancePct / 100.0);

            if (bullish && low <= neckline + tolerance && low >= neckline - tolerance) {
                return i;
            }
            if (!bullish && high >= neckline - tolerance && high <= neckline + tolerance) {
                return i;
            }
        }
        return -1;
    }

    private BarSeries loadCsv(String symbol, Path path) throws IOException {
        BarSeries series = new BaseBarSeriesBuilder().withName(symbol).build();
        List<String> lines = Files.readAllLines(path);
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

    static class TradeRecord {
        String symbol;
        String patternType;
        String direction;
        double entryPrice;
        int entryBar;
        Instant entryTime;
        double exitPrice;
        int exitBar;
        Instant exitTime;
        String exitReason;
        double stopInitial;
        double targetInitial;
        double pnlPct;
        boolean wasWinner;
        int holdingBars;
        List<PivotPoint> patternPivots;
    }
}
