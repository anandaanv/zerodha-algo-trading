package com.dtech.kitecon.event;

import java.time.Instant;

/**
 * Base interface for all domain events.
 * Published via Spring's ApplicationEventPublisher.
 */
public interface DomainEvent {
    Instant occurredAt();
    String eventType();
}
