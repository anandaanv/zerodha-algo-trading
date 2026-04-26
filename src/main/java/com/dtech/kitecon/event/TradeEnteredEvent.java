package com.dtech.kitecon.event;

import java.math.BigDecimal;
import java.time.Instant;

public record TradeEnteredEvent(
        Long signalId,
        Long orderId,
        String symbol,
        String direction,
        BigDecimal entryPrice,
        int quantity,
        String strategyType,
        Instant occurredAt
) implements DomainEvent {
    @Override
    public String eventType() { return "TRADE_ENTERED"; }
}
