package com.dtech.algo.screener.api;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Registry of all available screener implementations.
 * Spring auto-collects all Screener beans.
 */
@Component
public class ScreenerRegistry {

    private final Map<ScreenerType, Screener<?>> screeners = new EnumMap<>(ScreenerType.class);

    public ScreenerRegistry(List<Screener<?>> screenerList) {
        for (Screener<?> screener : screenerList) {
            screeners.put(screener.getType(), screener);
        }
    }

    public Optional<Screener<?>> get(ScreenerType type) {
        return Optional.ofNullable(screeners.get(type));
    }

    public Collection<Screener<?>> getAll() {
        return Collections.unmodifiableCollection(screeners.values());
    }

    public Set<ScreenerType> getRegisteredTypes() {
        return Collections.unmodifiableSet(screeners.keySet());
    }
}
