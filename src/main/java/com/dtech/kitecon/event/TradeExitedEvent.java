package com.dtech.kitecon.event;

import java.math.BigDecimal;
import java.time.Instant;

public record TradeExitedEvent(
        Long signalId,
        Long orderId,
        String symbol,
        String direction,
        BigDecimal exitPrice,
        BigDecimal pnl,
        String exitReason,
        Instant occurredAt
) implements DomainEvent {
    @Override
    public String eventType() { return "TRADE_EXITED"; }
}
