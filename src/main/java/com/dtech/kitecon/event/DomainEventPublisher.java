package com.dtech.kitecon.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes domain events through Spring's event system.
 * Services use this instead of calling each other directly for cross-cutting concerns.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DomainEventPublisher {

    private final ApplicationEventPublisher publisher;

    public void publish(DomainEvent event) {
        log.debug("[Event] Publishing: {} at {}", event.eventType(), event.occurredAt());
        publisher.publishEvent(event);
    }
}
