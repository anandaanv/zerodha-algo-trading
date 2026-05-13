package com.dtech.ta.patterns.classic;

import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.time.Instant;

/**
 * Immutable record representing a pivot point: bar index, timestamp, price, and type.
 * Reference: docs/spec-classic-patterns-port.md
 */
public record PivotPoint(int barIndex, Instant time, double price, PivotType type) {

    /**
     * Factory method to create a PivotPoint from a BarSeries and bar index.
     *
     * @param series the BarSeries
     * @param barIndex the index of the bar
     * @param type the PivotType (HIGH or LOW)
     * @return a new PivotPoint with price extracted from the bar's high or low
     */
    public static PivotPoint of(BarSeries series, int barIndex, PivotType type) {
        if (type == PivotType.BOTH) {
            throw new IllegalArgumentException("PivotType.BOTH not supported in factory method; use HIGH or LOW");
        }
        Bar bar = series.getBar(barIndex);
        double price = type == PivotType.HIGH
            ? bar.getHighPrice().doubleValue()
            : bar.getLowPrice().doubleValue();
        Instant time = bar.getEndTime();
        return new PivotPoint(barIndex, time, price, type);
    }
}
