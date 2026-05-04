package com.dtech.kitecon.simulation;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.data.Candle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.CandleRepository;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.simulation.strategy.SimulationStrategy;
// Ta4jZigZagBridge removed — ta4j ZigZag is too simple for impulse detection
// Using IncrementalZigZag in ImpulseSimulationStrategy instead
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import com.dtech.kitecon.trade.entity.TradeActionLog;
import com.dtech.kitecon.trade.entity.TradeOrder;
import com.dtech.kitecon.trade.entity.TradeSignal;
import com.dtech.kitecon.trade.enums.ExitReason;
import com.dtech.kitecon.trade.enums.TradeOrderStatus;
import com.dtech.kitecon.trade.enums.TradeStatus;
import com.dtech.kitecon.trade.enums.TradingSegment;
import com.dtech.kitecon.trade.repository.TradeOrderRepository;
import com.dtech.kitecon.trade.repository.TradeSignalRepository;
import com.dtech.kitecon.trade.service.TradeActionLogger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Main simulation orchestrator. Replays historical bars chronologically,
 * passing only time-truncated bar data to strategies — no future data leakage.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TradeSimulationService {

    private final CandleRepository candleRepository;
    private final InstrumentRepository instrumentRepository;
    private final TradeSignalRepository signalRepository;
    private final TradeOrderRepository orderRepository;
    private final TradeActionLogger actionLogger;
    private final List<SimulationStrategy> strategies;

    private SimulationContext activeContext;

    @org.springframework.beans.factory.annotation.Value("${simulation.wave.size:20}")
    private int waveSize;

    /**
     * Run a full simulation from start to end. Symbols are processed in waves
     * (default 20) — only the active wave's bars and indicator state are kept
     * in memory; previous waves are torn down via strategy.reset().
     */
    public SimulationResult run(String strategyType, List<String> symbols, String timeframe,
                                Instant from, Instant to, int stepMinutes) {
        SimulationStrategy strategy = strategies.stream()
                .filter(s -> s.getStrategyType().equals(strategyType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown strategy: " + strategyType));

        // One context accumulates stats across all waves
        SimulationContext ctx = new SimulationContext(strategyType, symbols, timeframe);
        ctx.setClock(new SimulationClock(from, to, stepMinutes));
        activeContext = ctx;
        strategy.reset();  // clear caches from previous runs

        // Partition symbols into waves so we don't hold all 200 stocks' bars+indicators in memory.
        int effectiveWaveSize = Math.max(1, waveSize);
        List<List<String>> waves = new ArrayList<>();
        for (int i = 0; i < symbols.size(); i += effectiveWaveSize) {
            waves.add(symbols.subList(i, Math.min(i + effectiveWaveSize, symbols.size())));
        }
        log.info("[Simulation] {} symbols partitioned into {} wave(s) of up to {} each",
                symbols.size(), waves.size(), effectiveWaveSize);

        int waveIdx = 0;
        for (List<String> waveSymbols : waves) {
            waveIdx++;
            log.info("[Simulation] Wave {}/{}: starting {} symbols", waveIdx, waves.size(), waveSymbols.size());
            Map<String, BarSeries> waveGrowingSeries = new HashMap<>();
            Map<String, BarSeries> waveFullSeries = new HashMap<>();
            runWave(strategy, waveSymbols, timeframe, from, to, stepMinutes, ctx, waveGrowingSeries, waveFullSeries);
            // Force-close any positions still open for this wave's symbols (wave-boundary timeout)
            forceCloseWavePositions(ctx, waveSymbols, waveGrowingSeries, waveFullSeries);
            // Drop strategy/indicator caches before loading next wave
            strategy.reset();
            System.gc();
            log.info("[Simulation] Wave {}/{} done — cumulative: {} signals, {} closed, PnL={}%",
                    waveIdx, waves.size(),
                    ctx.getTotalSignalsGenerated(), ctx.getClosedPositions().size(),
                    String.format("%.2f", ctx.getTotalPnlPct()));
        }

        log.info("[Simulation] Complete: {} signals, {} closed, {} open, PnL={}%",
                ctx.getTotalSignalsGenerated(), ctx.getClosedPositions().size(),
                ctx.getOpenPositions().size(), String.format("%.2f", ctx.getTotalPnlPct()));
        return buildResult(ctx);
    }

    /**
     * Runs the simulation time-loop for a single wave of symbols. Loads bars,
     * builds growing series, advances the clock, scans/exits per-tick.
     * Per-wave clock reuses the same from/to/stepMinutes — the time axis
     * is the same across waves; only the universe of stocks differs.
     * Output maps (growingSeries, fullSeries) are populated for MTM at wave boundary.
     */
    private void runWave(SimulationStrategy strategy, List<String> symbols, String timeframe,
                          Instant from, Instant to, int stepMinutes, SimulationContext ctx,
                          Map<String, BarSeries> growingSeries, Map<String, BarSeries> fullSeries) {
        SimulationClock clock = new SimulationClock(from, to, stepMinutes);
        ctx.setClock(clock);
        clock.start();

        // Preload full bar series per symbol from DB
        Interval interval = Interval.valueOf(timeframe);
        Map<String, BarSeries> localFullSeries = new HashMap<>();
        for (String symbol : symbols) {
            try {
                Instrument inst = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"});
                if (inst == null) {
                    log.debug("[Simulation] Instrument not found for {}", symbol);
                    continue;
                }
                // Load ALL available bars (match training's full-history lookback for identical ATR/ZigZag)
                Instant lookbackFrom = Instant.parse("2015-01-01T00:00:00Z");
                BarSeries series = loadBarSeries(inst, interval, lookbackFrom, to);
                if (series != null && series.getBarCount() > 0) {
                    localFullSeries.put(symbol, series);
                }
            } catch (Exception e) {
                log.warn("[Simulation] Failed to load series for {}: {}", symbol, e.getMessage());
            }
        }
        // Populate output parameter for wave-boundary MTM
        fullSeries.putAll(localFullSeries);
        // Preload exit-granularity bar series (e.g. 5m bars for 15m scan)
        Interval exitInterval = getExitInterval(interval);
        Map<String, BarSeries> exitSeries = new HashMap<>();
        if (exitInterval != interval) {
            for (String symbol : localFullSeries.keySet()) {
                try {
                    Instrument inst = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"});
                    Instant lookbackFrom = from.minus(30, java.time.temporal.ChronoUnit.DAYS);
                    BarSeries series = loadBarSeries(inst, exitInterval, lookbackFrom, to);
                    if (series != null && series.getBarCount() > 0) {
                        exitSeries.put(symbol, series);
                    }
                } catch (Exception e) {
                    log.warn("[Simulation] Failed to load exit series for {}: {}", symbol, e.getMessage());
                }
            }
        }
        int exitStepMinutes = exitInterval != interval ? 5 : stepMinutes;
        log.info("[Simulation] Loaded {} symbols, stepping {}m (exits {}m) from {} to {}",
                localFullSeries.size(), stepMinutes, exitStepMinutes, from, to);

        // Build growing series per symbol — add bars incrementally instead of truncating
        // This enables OnChange listeners (ZigZag, indicators) to auto-update
        Map<String, BarSeries> localGrowingSeries = new HashMap<>();
        Map<String, List<Bar>> sortedBars = new HashMap<>();
        Map<String, Integer> nextBarIndex = new HashMap<>();

        for (Map.Entry<String, BarSeries> entry : localFullSeries.entrySet()) {
            String symbol = entry.getKey();
            BarSeries full = entry.getValue();

            // Extract all bars sorted by time
            List<Bar> bars = new ArrayList<>();
            for (int i = 0; i < full.getBarCount(); i++) {
                bars.add(full.getBar(i));
            }

            // Create empty growing series and attach ZigZag bridge BEFORE adding bars
            // This ensures OnChange fires for every bar (matching training behavior)
            BarSeries growing = new BaseBarSeriesBuilder()
                    .withName(symbol + "_sim").build();

            // Pre-add bars before the simulation start time (lookback for indicators)
            int preAddCount = 0;
            for (Bar bar : bars) {
                if (!bar.getEndTime().isAfter(from)) {
                    growing.addBar(bar);
                    preAddCount++;
                }
            }

            localGrowingSeries.put(symbol, growing);
            sortedBars.put(symbol, bars);
            nextBarIndex.put(symbol, preAddCount);
            log.debug("[Simulation] {} pre-added {} lookback bars, {} total available",
                    symbol, preAddCount, bars.size());
        }

        // Populate output parameter for wave-boundary MTM
        growingSeries.putAll(localGrowingSeries);

        // Step through time. Per-tick, process stocks in parallel so the prediction
        // batcher receives concurrent submissions and can dispatch in batches.
        do {
            final Instant t = clock.getCurrentTime();
            ctx.setTotalSteps(ctx.getTotalSteps() + 1);

            localGrowingSeries.entrySet().parallelStream().forEach(entry -> {
                String symbol = entry.getKey();
                BarSeries growing = entry.getValue();

                // Add new bars up to current simulated time (OnChange fires → ZigZag auto-updates)
                List<Bar> bars = sortedBars.get(symbol);
                int nextIdx = nextBarIndex.getOrDefault(symbol, 0);
                while (nextIdx < bars.size() && !bars.get(nextIdx).getEndTime().isAfter(t)) {
                    growing.addBar(bars.get(nextIdx));
                    nextIdx++;
                }
                nextBarIndex.put(symbol, nextIdx);

                if (growing.getBarCount() < 10) return;

                // Use growing series as the "truncated" series — it only has bars up to t
                BarSeries truncated = growing;

                // Snapshot openPositions so this lambda iterates a stable list
                // (other parallel symbol-threads may mutate via processExit/scan).
                List<TradeSignal> openSnapshot;
                synchronized (ctx) {
                    openSnapshot = new ArrayList<>(ctx.getOpenPositions());
                }

                // 1. Check exits — sub-step through finer-grained bars if available
                BarSeries exitFull = exitSeries.get(symbol);
                boolean exitHandled = false;
                if (exitFull != null) {
                    // Sub-step: iterate 5-min bars within this 15-min window [t - stepMinutes, t]
                    Instant windowStart = t.minusSeconds((long) stepMinutes * 60);
                    for (int sub = 1; sub <= stepMinutes / exitStepMinutes; sub++) {
                        Instant subTime = windowStart.plusSeconds((long) sub * exitStepMinutes * 60);
                        BarSeries exitTruncated = BarSeriesTruncator.truncate(exitFull, subTime);
                        if (exitTruncated.getBarCount() < 2) return;
                        Bar exitBar = exitTruncated.getBar(exitTruncated.getEndIndex());
                        // Validate bar is within the current window — stale data means no 5m coverage
                        if (exitBar.getEndTime().isBefore(windowStart)) return;
                        exitHandled = true;

                        List<SimulationStrategy.ExitResult> exits = strategy.checkExits(ctx, openSnapshot, symbol, exitBar, truncated, exitTruncated);
                        for (SimulationStrategy.ExitResult exit : exits) {
                            processExit(ctx, exit, subTime);
                        }
                    }
                }
                if (!exitHandled) {
                    // Fallback: check exits on the scan-timeframe bar
                    Bar currentBar = truncated.getBar(truncated.getEndIndex());
                    List<SimulationStrategy.ExitResult> exits = strategy.checkExits(ctx, openSnapshot, symbol, currentBar, truncated, null);
                    for (SimulationStrategy.ExitResult exit : exits) {
                        processExit(ctx, exit, t);
                    }
                }

                // 2. Scan for new signals (limit to 2 open positions per symbol)
                long activeForSymbol = openSnapshot.stream()
                        .filter(s -> symbol.equals(s.getSymbol())).count();
                if (activeForSymbol < 2) {
                    List<TradeSignal> newSignals = strategy.scan(ctx, symbol, truncated);
                    for (TradeSignal sig : newSignals) {
                        sig.setSignalTime(t);
                        sig.setCandleTime(t);
                        if (sig.getStatus() == null) {
                            sig.setStatus(TradeStatus.ACTIVE);
                        }
                        signalRepository.save(sig);
                        synchronized (ctx) {
                            ctx.getOpenPositions().add(sig);
                            ctx.setTotalSignalsGenerated(ctx.getTotalSignalsGenerated() + 1);
                        }
                        try {
                            actionLogger.log(sig, TradeActionLog.TradeAction.SIGNAL_CREATED,
                                    sig.getEntryPrice(), "Sim signal", t);
                            actionLogger.log(sig, TradeActionLog.TradeAction.ENTRY_FILLED,
                                    sig.getEntryPrice(), "Sim paper fill", t);
                            // Create trade order for /trade-orders page visibility
                            TradeOrder order = TradeOrder.builder()
                                    .signal(sig)
                                    .symbol(sig.getSymbol())
                                    .underlyingSymbol(sig.getSymbol())
                                    .segment(TradingSegment.EQ)
                                    .direction(sig.getDirection())
                                    .quantity(1)
                                    .lotSize(1)
                                    .entryPrice(sig.getEntryPrice())
                                    .entryTime(t)
                                    .status(TradeOrderStatus.OPEN)
                                    .instrumentType("EQ")
                                    .instrumentToken(sig.getInstrumentToken() != null ? sig.getInstrumentToken() : 0L)
                                    .paperTrade(true)
                                    .stopLoss(sig.getStopLoss())
                                    .target(sig.getTarget())
                                    .underlyingEntryPrice(sig.getEntryPrice())
                                    .build();
                            orderRepository.save(order);
                        } catch (Exception e) {
                            log.debug("[Simulation] Failed to log signal: {}", e.getMessage());
                        }
                    }
                }
            });
        } while (clock.advance());

        clock.stop();
    }

    /** Close any positions still open for symbols in this wave at the last available close. */
    private void forceCloseWavePositions(SimulationContext ctx, List<String> waveSymbols,
                                         Map<String, BarSeries> growingSeries, Map<String, BarSeries> fullSeries) {
        java.util.Set<String> waveSet = new java.util.HashSet<>(waveSymbols);
        List<TradeSignal> toClose;
        synchronized (ctx) {
            toClose = new ArrayList<>();
            for (TradeSignal sig : ctx.getOpenPositions()) {
                if (waveSet.contains(sig.getSymbol())) toClose.add(sig);
            }
        }
        for (TradeSignal sig : toClose) {
            double entry = sig.getEntryPrice().doubleValue();
            double exit = entry;
            double pnl = 0.0;
            double pnlPct = 0.0;

            // Try to mark-to-market against the last available bar close
            BarSeries series = growingSeries.get(sig.getSymbol());
            if (series == null || series.getBarCount() == 0) {
                series = fullSeries.get(sig.getSymbol());
            }

            if (series != null && series.getBarCount() > 0) {
                Bar lastBar = series.getLastBar();
                exit = lastBar.getClosePrice().doubleValue();

                // Compute PnL respecting direction
                if (sig.getDirection().equals(com.dtech.kitecon.trade.enums.TradeDirection.LONG)) {
                    pnl = exit - entry;
                } else {
                    pnl = entry - exit;
                }
                pnlPct = (pnl / entry) * 100;
            } else {
                log.warn("[Simulation] No bar series available for wave-boundary MTM of {}", sig.getSymbol());
            }

            sig.setStatus(TradeStatus.COMPLETED);
            signalRepository.save(sig);
            synchronized (ctx) {
                ctx.getOpenPositions().remove(sig);
                ctx.getClosedPositions().add(sig);
                ctx.setTotalPnlPct(ctx.getTotalPnlPct() + pnlPct);
                if (pnlPct > 0) {
                    ctx.setWins(ctx.getWins() + 1);
                } else {
                    ctx.setLosses(ctx.getLosses() + 1);
                }
            }
            try {
                actionLogger.logWithPnl(sig, TradeActionLog.TradeAction.TIMEOUT_EXIT,
                        BigDecimal.valueOf(exit), BigDecimal.valueOf(pnlPct),
                        "Wave boundary close", clockNow(ctx));
            } catch (Exception ignored) {}
        }
        if (!toClose.isEmpty()) {
            log.info("[Simulation] Wave-boundary closed {} open position(s)", toClose.size());
        }
    }

    private Instant clockNow(SimulationContext ctx) {
        try { return ctx.getClock().getCurrentTime(); } catch (Exception e) { return Instant.now(); }
    }

    /**
     * Run a single step (for manual stepping via API).
     */
    public SimulationStepResult step() {
        throw new UnsupportedOperationException("step mode not yet implemented");
    }

    public SimulationContext getStatus() { return activeContext; }

    public void reset() { activeContext = null; }

    private BarSeries loadBarSeries(Instrument inst, Interval interval, Instant from, Instant to) {
        List<Candle> candles = candleRepository
                .findAllByInstrumentAndTimeframeAndTimestampBetween(inst, interval, from, to);
        candles.sort(Comparator.comparing(Candle::getTimestamp));
        BarSeries series = new BaseBarSeriesBuilder().withName(inst.getTradingsymbol()).build();
        candles.forEach(c -> series.addBar(BarsLoader.getBar(
                c.getOpen(), c.getHigh(), c.getLow(), c.getClose(),
                Optional.ofNullable(c.getVolume()).orElse(0L), c.getTimestamp())));
        return series;
    }

    /**
     * Returns the finer-grained interval used for exit monitoring.
     * E.g. FifteenMinute signals → exits checked on FiveMinute bars.
     */
    private Interval getExitInterval(Interval scanInterval) {
        return switch (scanInterval) {
            case FifteenMinute -> Interval.FiveMinute;
            case OneHour -> Interval.OneHour;  // no sub-stepping — exits checked only on 1h close
            case ThirtyMinute -> Interval.FiveMinute;
            default -> scanInterval; // same TF for exit if no finer available
        };
    }

    private void processExit(SimulationContext ctx, SimulationStrategy.ExitResult exit, Instant candleTime) {
        TradeSignal sig = exit.signal();
        sig.setStatus(TradeStatus.COMPLETED);
        signalRepository.save(sig);
        synchronized (ctx) {
            ctx.getOpenPositions().remove(sig);
            ctx.getClosedPositions().add(sig);
            ctx.setTotalPnlPct(ctx.getTotalPnlPct() + exit.pnlPct());
            if (exit.pnlPct() > 0) ctx.setWins(ctx.getWins() + 1);
            else ctx.setLosses(ctx.getLosses() + 1);
        }
        try {
            actionLogger.logWithPnl(sig,
                    TradeActionLog.TradeAction.valueOf(exit.exitReason()),
                    BigDecimal.valueOf(exit.exitPrice()),
                    BigDecimal.valueOf(exit.pnlPct()),
                    String.format("Sim exit @ %.2f", exit.exitPrice()), candleTime);
            List<TradeOrder> orders = orderRepository.findBySignalAndStatus(sig, TradeOrderStatus.OPEN);
            for (TradeOrder order : orders) {
                order.setExitPrice(BigDecimal.valueOf(exit.exitPrice()));
                order.setExitTime(candleTime);
                order.setStatus(TradeOrderStatus.CLOSED);
                order.setExitReason(ExitReason.valueOf(exit.exitReason()));
                order.setRealisedPnl(BigDecimal.valueOf(exit.pnlPct()));
                order.setUnderlyingExitPrice(BigDecimal.valueOf(exit.exitPrice()));
                order.setUpdatedAt(candleTime);
                orderRepository.save(order);
            }
        } catch (Exception e) {
            log.debug("[Simulation] Failed to log exit: {}", e.getMessage());
        }
    }

    private SimulationResult buildResult(SimulationContext ctx) {
        return new SimulationResult(
                ctx.getTotalSteps(),
                ctx.getTotalSignalsGenerated(),
                ctx.getClosedPositions().size(),
                ctx.getOpenPositions().size(),
                ctx.getWins(),
                ctx.getLosses(),
                ctx.getTotalPnlPct(),
                ctx.getWins() > 0 || ctx.getLosses() > 0
                        ? (double) ctx.getWins() / (ctx.getWins() + ctx.getLosses()) * 100
                        : 0
        );
    }

    public record SimulationResult(int steps, int signalsGenerated, int closed, int open,
                                   int wins, int losses, double totalPnlPct, double winRatePct) {}

    public record SimulationStepResult(Instant time, int newSignals, int exits, List<String> actions) {}
}
