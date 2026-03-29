package com.dtech.ta.elliott;

import com.dtech.algo.series.Interval;
import com.dtech.chartpattern.zigzag.ZigZagParams;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.KiteconApplication;
import com.dtech.kitecon.data.Candle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.CandleRepository;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.service.copilot.MarketStructureService;
import com.dtech.kitecon.service.copilot.dto.MarketStructureData;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.bars.TimeBarBuilder;
import org.ta4j.core.num.DoubleNumFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Integration simulator for Elliott Wave backtesting analysis on real INFY daily candles.
 *
 * This simulator:
 * 1. Loads INFY daily candles from the database
 * 2. Builds a BarSeries from the candles (sorted by timestamp ascending)
 * 3. Runs a sliding window analysis: for each candle from MIN_HISTORY onwards,
 *    truncates the BarSeries and runs Elliott Wave analysis on the truncated data
 * 4. Extracts trading signals from wave counts and patterns with sufficient confidence
 * 5. For each signal, walks forward to detect win/loss outcomes
 * 6. Prints a formatted backtest table with all signals and their outcomes
 * 7. Logs simulation completion
 *
 * Prerequisites:
 * - MySQL running with INFY instrument + daily candles
 * - test profile active (MySQL test schema or dev schema)
 *
 * Run: ./gradlew test --tests "*.ElliottWaveBacktestSimulator"
 */
@Slf4j
@SpringBootTest(classes = KiteconApplication.class)
@ActiveProfiles("integration")
class ElliottWaveBacktestSimulator {

    // ── Test constants ────────────────────────────────────────────────────────
    private static final String SYMBOL = "INFY";
    private static final int MIN_HISTORY = 60;           // minimum bars before starting analysis
    private static final int CANDLE_STEP = 5;            // run analysis every 5th candle for performance
    private static final double MIN_WAVE_SCORE = 65.0;
    private static final double MIN_PATTERN_CONFIDENCE = 70.0;
    private static final int MAX_FORWARD_BARS = 50;      // max bars to walk forward for P&L calculation

    // ── Records for signal and outcome tracking ────────────────────────────────
    record TradeSignal(
        int signalBarIndex,
        String direction,
        double entry,
        double stop,
        double target,
        double score,
        String source,
        LocalDate signalDate
    ) {}

    record TradeOutcome(
        TradeSignal signal,
        String result,    // WIN / LOSS / TIMEOUT
        int barsToOutcome,
        double pnlR        // P&L as a risk ratio (positive = gain, negative = loss)
    ) {}

    // ── Injected dependencies ─────────────────────────────────────────────────
    @Autowired private InstrumentRepository instrumentRepository;
    @Autowired private CandleRepository candleRepository;
    @Autowired private ZigZagService zigzagService;
    @Autowired private MarketStructureService marketStructureService;
    @Autowired private ElliottWaveAnalyzer elliottWaveAnalyzer;

    private Instrument instrument;

    @BeforeEach
    void setUp() {
        instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(SYMBOL, new String[]{"NSE"});
        if (instrument == null) {
            log.error("INFY instrument not found in DB — aborting simulation");
            return;
        }
    }

    @Test
    void simulate() {
        log.info("\n========== ELLIOTT WAVE BACKTEST: {} ==========", SYMBOL);

        // ── Step 1: Load INFY daily candles, sorted by timestamp ascending ────
        List<Candle> allCandles = candleRepository.findAllByInstrumentAndTimeframe(instrument, Interval.Day);
        allCandles.sort(Comparator.comparing(Candle::getTimestamp));

        if (allCandles.size() <= MIN_HISTORY + MAX_FORWARD_BARS) {
            log.error("Not enough candles: {} (need > {})", allCandles.size(), MIN_HISTORY + MAX_FORWARD_BARS);
            return;
        }

        log.info("[Backtest] Loaded {} daily candles for {}", allCandles.size(), SYMBOL);

        // ── Step 2: Build full BarSeries ──────────────────────────────────────
        BarSeries fullSeries = buildBarSeriesFromCandles(SYMBOL, allCandles);

        // ── Step 3: Sliding window loop ───────────────────────────────────────
        List<TradeSignal> allSignals = new ArrayList<>();
        List<TradeOutcome> allOutcomes = new ArrayList<>();

        ZigZagParams params = zigzagService.resolveParams(SYMBOL, Interval.Day);

        for (int i = MIN_HISTORY; i < allCandles.size() - MAX_FORWARD_BARS; i += CANDLE_STEP) {
            Candle currentCandle = allCandles.get(i);
            int signalBarIndex = i;

            try {
                // Build truncated BarSeries up to current candle
                BarSeries truncatedSeries = buildBarSeriesFromCandles(SYMBOL, allCandles.subList(0, i + 1));

                // Compute ZigZag pivots
                List<ZigZagPoint> pivots = zigzagService.detect(truncatedSeries, params);
                if (pivots.size() < 5) {
                    continue;  // Need minimum structure
                }

                // Compute market structure
                MarketStructureData msd = marketStructureService.analyse(pivots, "day");

                // Run Elliott Wave analysis (single timeframe: "day")
                Map<String, List<ZigZagPoint>> pivotsByTf = new LinkedHashMap<>();
                pivotsByTf.put("day", pivots);

                Map<String, MarketStructureData> structureByTf = new LinkedHashMap<>();
                structureByTf.put("day", msd);

                Map<String, BarSeries> seriesByTf = new LinkedHashMap<>();
                seriesByTf.put("day", truncatedSeries);

                ElliottWaveAnalysis analysis = elliottWaveAnalyzer.analyze(
                        pivotsByTf, structureByTf, seriesByTf,
                        List.of("day"), "day");

                // ── Step 3f: Extract signals from analysis ──────────────────
                List<TradeSignal> windowSignals = extractSignalsFromAnalysis(
                        analysis, truncatedSeries, signalBarIndex, currentCandle);

                // ── Step 4: Deduplicate consecutive signals (same direction) ──
                for (TradeSignal signal : windowSignals) {
                    boolean isDuplicate = allSignals.stream()
                            .filter(s -> s.signalBarIndex >= signalBarIndex - CANDLE_STEP)
                            .anyMatch(s -> s.direction.equals(signal.direction));
                    if (!isDuplicate) {
                        allSignals.add(signal);
                    }
                }

            } catch (Exception e) {
                log.debug("[Backtest] Window at bar {} failed: {}", signalBarIndex, e.getMessage());
                // Skip this window
            }
        }

        // ── Step 5: Walk forward for each signal to compute P&L ──────────────
        for (TradeSignal signal : allSignals) {
            TradeOutcome outcome = walkForwardToOutcome(allCandles, signal);
            allOutcomes.add(outcome);
        }

        // ── Step 6: Print formatted backtest table ──────────────────────────
        printBacktestTable(allOutcomes);

        // ── Step 7: Print summary stats ───────────────────────────────────────
        printBacktestSummary(allOutcomes);

        log.info("\n========== BACKTEST COMPLETE ==========\n");
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds a BarSeries from a list of Candle objects.
     * Candles are expected to be sorted by timestamp ascending.
     */
    private BarSeries buildBarSeriesFromCandles(String symbol, List<Candle> candles) {
        BarSeries series = new BaseBarSeriesBuilder().withName(symbol).build();
        for (Candle c : candles) {
            Bar bar = new TimeBarBuilder(DoubleNumFactory.getInstance())
                    .openPrice(c.getOpen())
                    .highPrice(c.getHigh())
                    .lowPrice(c.getLow())
                    .closePrice(c.getClose())
                    .volume(c.getVolume() != null ? c.getVolume() : 0)
                    .endTime(c.getTimestamp())
                    .timePeriod(Duration.ofDays(1))
                    .build();
            series.addBar(bar);
        }
        return series;
    }

    /**
     * Extracts trading signals from the Elliott Wave analysis.
     *
     * Signal sources:
     * 1. Wave counts with totalScore() >= MIN_WAVE_SCORE
     * 2. Patterns with confidence >= MIN_PATTERN_CONFIDENCE and status CONFIRMED/WATCHING
     *
     * Stop/Target calculation:
     * - LONG: stop = w4SupportZoneLow if available, else use close * 0.97 (3% stop)
     *         target = 1st Fib target or close * (1 + 1.618 * risk distance)
     * - SHORT: stop = w1Origin if available, else use close * 1.03 (3% stop)
     *          target = 1st Fib target or close * (1 - 1.618 * risk distance)
     */
    private List<TradeSignal> extractSignalsFromAnalysis(
            ElliottWaveAnalysis analysis,
            BarSeries series,
            int barIndex,
            Candle currentCandle) {

        List<TradeSignal> signals = new ArrayList<>();
        double entryPrice = currentCandle.getClose();
        LocalDate signalDate = currentCandle.getTimestamp()
                .atZone(java.time.ZoneId.of("Asia/Kolkata"))
                .toLocalDate();

        TfContext dayCtx = analysis.getTfContexts().get("day");

        // ── Extract signals from wave counts ──────────────────────────────────
        for (WaveCount wc : analysis.getWaveCounts()) {
            if (wc.totalScore() < MIN_WAVE_SCORE) {
                continue;
            }

            boolean isBullish = wc.isBullish();
            String direction = isBullish ? "LONG" : "SHORT";
            String source = "Wave_" + wc.getWaveType();

            double score = wc.totalScore();
            double stop = calculateStopForWaveCount(wc, entryPrice, isBullish, dayCtx);
            double target = calculateTargetForWaveCount(wc, entryPrice, isBullish, stop);

            signals.add(new TradeSignal(barIndex, direction, entryPrice, stop, target, score, source, signalDate));
        }

        // ── Extract signals from patterns ────────────────────────────────────
        for (PatternMatch pattern : analysis.getAllPatterns()) {
            if (pattern.getConfidence() < MIN_PATTERN_CONFIDENCE) {
                continue;
            }
            if (pattern.getStatus() != PatternStatus.CONFIRMED
                    && pattern.getStatus() != PatternStatus.WATCHING) {
                continue;
            }

            boolean isBullish = pattern.isBullish();
            String direction = isBullish ? "LONG" : "SHORT";
            String source = "Pattern_" + pattern.getType();

            double score = pattern.getConfidence();
            double stop = pattern.getInvalidation() != null ? pattern.getInvalidation()
                    : (isBullish ? entryPrice * 0.97 : entryPrice * 1.03);
            double target = pattern.getTarget() != null ? pattern.getTarget()
                    : (isBullish ? entryPrice * 1.05 : entryPrice * 0.95);

            signals.add(new TradeSignal(barIndex, direction, entryPrice, stop, target, score, source, signalDate));
        }

        return signals;
    }

    /**
     * Calculate stop loss for a LONG/SHORT based on wave count context.
     */
    private double calculateStopForWaveCount(WaveCount wc, double entry, boolean isBullish, TfContext ctx) {
        if (isBullish) {
            // LONG: use w4SupportZoneLow from TfContext if available
            if (ctx != null && ctx.getW4SupportMin() != null) {
                return ctx.getW4SupportMin();
            }
            // Fallback: 3% below entry
            return entry * 0.97;
        } else {
            // SHORT: use w1Origin if available (correctionOrigin)
            if (ctx != null && ctx.getCorrectionOrigin() != null) {
                return ctx.getCorrectionOrigin();
            }
            // Fallback: 3% above entry
            return entry * 1.03;
        }
    }

    /**
     * Calculate target for a LONG/SHORT based on wave count.
     * Uses Fibonacci extension if available (wave3ExtensionPct), else uses risk × 1.618.
     */
    private double calculateTargetForWaveCount(WaveCount wc, double entry, boolean isBullish, double stop) {
        double risk = Math.abs(entry - stop);
        Double fibTarget = wc.getWave3ExtensionPct();

        if (isBullish) {
            if (fibTarget != null && fibTarget > 0) {
                return entry + (risk * fibTarget);
            }
            return entry + (risk * 1.618);
        } else {
            if (fibTarget != null && fibTarget > 0) {
                return entry - (risk * fibTarget);
            }
            return entry - (risk * 1.618);
        }
    }

    /**
     * Walk forward from a signal to detect the first WIN/LOSS/TIMEOUT outcome.
     *
     * For LONG:
     *  - WIN: if low <= stop → loss
     *  - WIN: if high >= target → win
     *  - TIMEOUT: if neither hit after MAX_FORWARD_BARS
     *
     * For SHORT:
     *  - LOSS: if high >= stop → loss
     *  - WIN: if low <= target → win
     *  - TIMEOUT: if neither hit after MAX_FORWARD_BARS
     */
    private TradeOutcome walkForwardToOutcome(List<Candle> allCandles, TradeSignal signal) {
        int startIdx = signal.signalBarIndex + 1;
        int endIdx = Math.min(startIdx + MAX_FORWARD_BARS, allCandles.size());

        for (int i = startIdx; i < endIdx; i++) {
            Candle c = allCandles.get(i);
            int barsToOutcome = i - signal.signalBarIndex;

            boolean isLong = "LONG".equals(signal.direction);

            if (isLong) {
                // Check LOSS first (stop hit)
                if (c.getLow() <= signal.stop) {
                    double pnlR = -(signal.entry - signal.stop) / (signal.entry - signal.stop);
                    return new TradeOutcome(signal, "LOSS", barsToOutcome, pnlR);
                }
                // Check WIN (target hit)
                if (c.getHigh() >= signal.target) {
                    double pnlR = (signal.target - signal.entry) / (signal.entry - signal.stop);
                    return new TradeOutcome(signal, "WIN", barsToOutcome, pnlR);
                }
            } else {
                // SHORT
                // Check LOSS first (stop hit)
                if (c.getHigh() >= signal.stop) {
                    double pnlR = -(signal.stop - signal.entry) / (signal.entry - signal.stop);
                    return new TradeOutcome(signal, "LOSS", barsToOutcome, pnlR);
                }
                // Check WIN (target hit)
                if (c.getLow() <= signal.target) {
                    double pnlR = (signal.entry - signal.target) / (signal.entry - signal.stop);
                    return new TradeOutcome(signal, "WIN", barsToOutcome, pnlR);
                }
            }
        }

        // TIMEOUT: no outcome within MAX_FORWARD_BARS
        return new TradeOutcome(signal, "TIMEOUT", MAX_FORWARD_BARS, 0.0);
    }

    /**
     * Print a formatted backtest table with all signals and outcomes.
     */
    private void printBacktestTable(List<TradeOutcome> outcomes) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("┌─────────────┬──────┬─────────┬──────────┬──────────┬────────┬──────────┬─────────┬────────┬────────────┐\n");
        sb.append("│    Date     │ Dir  │  Entry  │   Stop   │  Target  │ Score  │  Source  │ Result  │ Bars   │  PnL (R)   │\n");
        sb.append("├─────────────┼──────┼─────────┼──────────┼──────────┼────────┼──────────┼─────────┼────────┼────────────┤\n");

        for (TradeOutcome outcome : outcomes) {
            TradeSignal sig = outcome.signal;
            String dateStr = sig.signalDate.toString();
            String dirStr = sig.direction.equals("LONG") ? "L" : "S";
            String entryStr = String.format("%.2f", sig.entry);
            String stopStr = String.format("%.2f", sig.stop);
            String targetStr = String.format("%.2f", sig.target);
            String scoreStr = String.format("%.0f", sig.score);
            String sourceStr = sig.source.length() > 8 ? sig.source.substring(0, 8) : sig.source;
            String resultStr = String.format("%-7s", outcome.result);
            String barsStr = String.format("%d", outcome.barsToOutcome);
            String pnlStr = String.format("%+.2f", outcome.pnlR);

            sb.append(String.format("│ %-11s │ %-4s │ %-7s │ %-8s │ %-8s │ %-6s │ %-8s │ %s │ %-6s │ %10s │\n",
                    dateStr, dirStr, entryStr, stopStr, targetStr, scoreStr, sourceStr, resultStr, barsStr, pnlStr));
        }

        sb.append("└─────────────┴──────┴─────────┴──────────┴──────────┴────────┴──────────┴─────────┴────────┴────────────┘\n");
        System.out.println(sb);
        log.info("Backtest table printed with {} outcomes", outcomes.size());
    }

    /**
     * Print summary statistics for the backtest.
     */
    private void printBacktestSummary(List<TradeOutcome> outcomes) {
        if (outcomes.isEmpty()) {
            System.out.println("\nNo outcomes to summarize.\n");
            return;
        }

        long wins = outcomes.stream().filter(o -> "WIN".equals(o.result)).count();
        long losses = outcomes.stream().filter(o -> "LOSS".equals(o.result)).count();
        long timeouts = outcomes.stream().filter(o -> "TIMEOUT".equals(o.result)).count();
        double totalPnL = outcomes.stream().mapToDouble(o -> o.pnlR).sum();
        double avgPnL = outcomes.stream().mapToDouble(o -> o.pnlR).average().orElse(0.0);
        double winRate = outcomes.isEmpty() ? 0.0 : (wins * 100.0) / (outcomes.size());

        System.out.println("\n" + "=".repeat(70));
        System.out.println("BACKTEST SUMMARY");
        System.out.println("=".repeat(70));
        System.out.printf("Total Signals:        %d\n", outcomes.size());
        System.out.printf("Wins:                 %d\n", wins);
        System.out.printf("Losses:               %d\n", losses);
        System.out.printf("Timeouts:             %d\n", timeouts);
        System.out.printf("Win Rate:             %.1f%%\n", winRate);
        System.out.printf("Total PnL (R):        %+.2f\n", totalPnL);
        System.out.printf("Avg PnL (R):          %+.2f\n", avgPnL);
        System.out.println("=".repeat(70) + "\n");

        log.info("Backtest summary: {} wins, {} losses, {} timeouts (win rate {}%)",
                wins, losses, timeouts, String.format("%.1f", winRate));
    }
}
