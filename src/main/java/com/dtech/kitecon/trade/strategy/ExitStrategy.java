package com.dtech.kitecon.trade.strategy;

import com.dtech.kitecon.trade.entity.TradeSignal;
import java.math.BigDecimal;

public interface ExitStrategy {
    /** Full context-aware evaluation (simulation + future live use) */
    ExitDecision evaluate(TradeSignal signal, ExitContext ctx);

    /** Backward-compatible shorthand for live trading */
    default ExitDecision evaluate(TradeSignal signal, BigDecimal currentLtp, BigDecimal underlyingLtp) {
        return evaluate(signal, ExitContext.simple(currentLtp, underlyingLtp));
    }
}
