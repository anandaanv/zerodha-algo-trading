package com.dtech.kitecon.event;

import java.time.Instant;

public record ScreenerCompletedEvent(
        String screenerType,
        String screenerName,
        int resultCount,
        long durationMs,
        Instant occurredAt
) implements DomainEvent {
    @Override
    public String eventType() { return "SCREENER_COMPLETED"; }
}
