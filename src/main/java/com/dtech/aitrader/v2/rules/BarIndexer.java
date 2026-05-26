package com.dtech.aitrader.v2.rules;

import org.ta4j.core.BarSeries;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps a pivot {@link Instant} to its bar index in a {@link BarSeries}. Built once per rule
 * evaluation so the per-pivot lookup stays O(1).
 *
 * <p>Lookup returns {@code -1} when no bar with that exact end-time exists in the series — the
 * caller should treat this as "pivot dropped" and skip rather than crash.
 */
public final class BarIndexer {

    private final Map<Instant, Integer> byEndTime;

    public BarIndexer(BarSeries series) {
        this.byEndTime = new HashMap<>(series.getBarCount() * 2);
        for (int i = series.getBeginIndex(); i <= series.getEndIndex(); i++) {
            byEndTime.put(series.getBar(i).getEndTime(), i);
        }
    }

    /** Bar index whose endTime exactly matches {@code t}, or {@code -1} if absent. */
    public int find(Instant t) {
        Integer idx = byEndTime.get(t);
        return idx == null ? -1 : idx;
    }
}
