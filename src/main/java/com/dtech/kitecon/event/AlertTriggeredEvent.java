package com.dtech.kitecon.event;

import java.time.Instant;

public record AlertTriggeredEvent(
        String alertType,
        String symbol,
        String timeframe,
        double price,
        String message,
        Instant occurredAt
) implements DomainEvent {
    @Override
    public String eventType() { return "ALERT_TRIGGERED"; }
}
