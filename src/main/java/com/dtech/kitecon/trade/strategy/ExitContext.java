package com.dtech.kitecon.trade.strategy;

import com.dtech.kitecon.trade.enums.TradingSegment;
import java.math.BigDecimal;

/**
 * Context passed to exit strategies for momentum-aware exit decisions.
 */
public record ExitContext(
    BigDecimal ltp,
    BigDecimal underlyingLtp,
    int barsInTrade,
    int barsSinceLastSlabAdvance,
    double macdLine,
    double macdSignal,
    double macdHistogram,
    double rsi,
    double macdLine5m,
    TradingSegment segment
) {
    /** Simple context for live trading where indicators aren't pre-computed */
    public static ExitContext simple(BigDecimal ltp, BigDecimal underlyingLtp) {
        return new ExitContext(ltp, underlyingLtp, 0, 0, 0.0, 0.0, 0.0, 50.0, 0.0, TradingSegment.EQ);
    }
}
