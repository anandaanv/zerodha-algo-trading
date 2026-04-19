package com.dtech.kitecon.simulation.strategy;

import com.dtech.kitecon.simulation.SimulationContext;
import com.dtech.kitecon.trade.entity.TradeSignal;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import java.util.List;

public interface SimulationStrategy {
    /** Check if any new signals should be generated at this timestamp */
    List<TradeSignal> scan(SimulationContext ctx, String symbol, BarSeries truncatedSeries);

    /** Check open positions against the current bar. BarSeries used for indicator computation. */
    List<ExitResult> checkExits(SimulationContext ctx, List<TradeSignal> openPositions, String symbol,
                                 Bar currentBar, BarSeries truncatedSeries, BarSeries exitSeries5m);

    String getStrategyType();

    /** Reset internal caches between simulation runs */
    default void reset() {}

    record ExitResult(TradeSignal signal, String exitReason, double exitPrice, double pnlPct) {}
}
