package com.dtech.aitrader.event;

import com.dtech.aitrader.model.PatternSignal;
import org.springframework.context.ApplicationEvent;

/**
 * Spring application event published when a fresh pattern signal is detected.
 * Listeners can act on this event to invoke PatternAgent for trade decisions.
 */
public class PatternSignalEvent extends ApplicationEvent {
    private final PatternSignal signal;

    public PatternSignalEvent(Object source, PatternSignal signal) {
        super(source);
        this.signal = signal;
    }

    public PatternSignal getSignal() {
        return signal;
    }
}
