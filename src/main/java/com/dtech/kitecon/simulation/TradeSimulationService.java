package com.dtech.kitecon.simulation;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.data.Candle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.CandleRepository;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.simulation.strategy.SimulationStrategy;
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

    /**
     * Run a full simulation from start to end.
     */
    public SimulationResult run(String strategyType, List<String> symbols, String timeframe,
                                Instant from, Instant to, int stepMinutes) {
        SimulationStrategy strategy = strategies.stream()
                .filter(s -> s.getStrategyType().equals(strategyType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown strategy: " + strategyType));

        SimulationClock clock = new SimulationClock(from, to, stepMinutes);
        SimulationContext ctx = new SimulationContext(clock, strategyType, symbols, timeframe);
        activeContext = ctx;
        strategy.reset();  // clear caches from previous runs
        clock.start();

        // Preload full bar series per symbol from DB
        Interval interval = Interval.valueOf(timeframe);
        Map<String, BarSeries> fullSeries = new HashMap<>();
        for (String symbol : symbols) {
            try {
                Instrument inst = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"});
                if (inst == null) {
                    log.debug("[Simulation] Instrument not found for {}", symbol);
                    continue;
                }
                // Load bars with 1-year lookback for ZigZag + indicators (not just the sim window)
                Instant lookbackFrom = from.minus(365, java.time.temporal.ChronoUnit.DAYS);
                BarSeries series = loadBarSeries(inst, interval, lookbackFrom, to);
                if (series != null && series.getBarCount() > 0) {
                    fullSeries.put(symbol, series);
                }
            } catch (Exception e) {
                log.warn("[Simulation] Failed to load series for {}: {}", symbol, e.getMessage());
            }
        }
        log.info("[Simulation] Loaded {} symbols, stepping {}m from {} to {}", fullSeries.size(), stepMinutes, from, to);

        // Step through time
        do {
            Instant t = clock.getCurrentTime();
            ctx.setTotalSteps(ctx.getTotalSteps() + 1);

            for (Map.Entry<String, BarSeries> entry : fullSeries.entrySet()) {
                String symbol = entry.getKey();
                BarSeries full = entry.getValue();

                // CRITICAL: truncate to current simulated time — no future data
                BarSeries truncated = BarSeriesTruncator.truncate(full, t);
                if (truncated.getBarCount() < 10) continue;

                // Current bar = last bar in truncated series
                Bar currentBar = truncated.getBar(truncated.getEndIndex());

                // 1. Check exits for open positions
                List<SimulationStrategy.ExitResult> exits = strategy.checkExits(ctx, ctx.getOpenPositions(), symbol, currentBar);
                for (SimulationStrategy.ExitResult exit : exits) {
                    TradeSignal sig = exit.signal();
                    sig.setStatus(TradeStatus.COMPLETED);
                    signalRepository.save(sig);
                    ctx.getOpenPositions().remove(sig);
                    ctx.getClosedPositions().add(sig);
                    ctx.setTotalPnlPct(ctx.getTotalPnlPct() + exit.pnlPct());
                    if (exit.pnlPct() > 0) ctx.setWins(ctx.getWins() + 1);
                    else ctx.setLosses(ctx.getLosses() + 1);
                    try {
                        actionLogger.logWithPnl(sig,
                                TradeActionLog.TradeAction.valueOf(exit.exitReason()),
                                BigDecimal.valueOf(exit.exitPrice()),
                                BigDecimal.valueOf(exit.pnlPct()),
                                String.format("Sim exit @ %.2f", exit.exitPrice()));
                        // Close the trade order
                        List<TradeOrder> orders = orderRepository.findBySignalAndStatus(sig, TradeOrderStatus.OPEN);
                        for (TradeOrder order : orders) {
                            order.setExitPrice(BigDecimal.valueOf(exit.exitPrice()));
                            order.setExitTime(t);
                            order.setStatus(TradeOrderStatus.CLOSED);
                            order.setExitReason(ExitReason.valueOf(exit.exitReason()));
                            order.setRealisedPnl(BigDecimal.valueOf(exit.pnlPct()));
                            order.setUnderlyingExitPrice(BigDecimal.valueOf(exit.exitPrice()));
                            order.setUpdatedAt(t);
                            orderRepository.save(order);
                        }
                    } catch (Exception e) {
                        log.debug("[Simulation] Failed to log exit: {}", e.getMessage());
                    }
                }

                // 2. Scan for new signals (limit to 2 open positions per symbol)
                long activeForSymbol = ctx.getOpenPositions().stream()
                        .filter(s -> symbol.equals(s.getSymbol())).count();
                if (activeForSymbol < 2) {
                    List<TradeSignal> newSignals = strategy.scan(ctx, symbol, truncated);
                    for (TradeSignal sig : newSignals) {
                        sig.setSignalTime(t);
                        sig.setStatus(TradeStatus.ACTIVE);
                        signalRepository.save(sig);
                        ctx.getOpenPositions().add(sig);
                        ctx.setTotalSignalsGenerated(ctx.getTotalSignalsGenerated() + 1);
                        try {
                            actionLogger.log(sig, TradeActionLog.TradeAction.SIGNAL_CREATED,
                                    sig.getEntryPrice(), "Sim signal");
                            actionLogger.log(sig, TradeActionLog.TradeAction.ENTRY_FILLED,
                                    sig.getEntryPrice(), "Sim paper fill");
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
            }
        } while (clock.advance());

        clock.stop();
        log.info("[Simulation] Complete: {} steps, {} signals, {} closed, {} open",
                ctx.getTotalSteps(), ctx.getTotalSignalsGenerated(),
                ctx.getClosedPositions().size(), ctx.getOpenPositions().size());

        return buildResult(ctx);
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
