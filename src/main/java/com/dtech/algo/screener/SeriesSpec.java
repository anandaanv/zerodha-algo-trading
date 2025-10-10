package com.dtech.algo.screener;

import com.dtech.algo.screener.enums.SeriesEnum;

/**
 * Simple container for series specification.
 * Represents a combination of a series reference (SPOT, FUT, CE1, PE-1, etc.)
 * and a timeframe/interval (e.g., "5MINUTE", "DAY", "HOUR").
 */
public record SeriesSpec(SeriesEnum reference, String interval) {

    public static SeriesSpec of(SeriesEnum reference, String interval) {
        return new SeriesSpec(reference, interval);
    }

    public static SeriesSpec of(String reference, String interval) {
        return new SeriesSpec(SeriesEnum.fromString(reference), interval);
    }
}
