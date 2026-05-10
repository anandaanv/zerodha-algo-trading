package com.dtech.ta.patterns.classic;

import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Utility to compute average bar length (median of high-low range) over a series range.
 * Reference: docs/spec-classic-patterns-port.md (avgBarLength definition)
 */
public class AvgBarLength {

    /**
     * Compute the median bar length (high - low) over [fromBarIndex, toBarIndex] inclusive.
     *
     * @param series the BarSeries
     * @param fromBarIndex starting index (inclusive)
     * @param toBarIndex ending index (inclusive)
     * @return median bar length; returns 0 if range is empty
     * @throws IllegalArgumentException if fromBarIndex > toBarIndex or indices out of bounds
     */
    public static double median(BarSeries series, int fromBarIndex, int toBarIndex) {
        if (fromBarIndex > toBarIndex) {
            throw new IllegalArgumentException("fromBarIndex must not be greater than toBarIndex");
        }
        if (fromBarIndex < 0 || toBarIndex > series.getEndIndex()) {
            throw new IllegalArgumentException("bar indices out of bounds");
        }

        List<Double> ranges = new ArrayList<>();
        for (int i = fromBarIndex; i <= toBarIndex; i++) {
            Bar bar = series.getBar(i);
            double range = bar.getHighPrice().doubleValue() - bar.getLowPrice().doubleValue();
            ranges.add(range);
        }

        if (ranges.isEmpty()) {
            return 0.0;
        }

        Collections.sort(ranges);
        int size = ranges.size();

        if (size % 2 == 1) {
            return ranges.get(size / 2);
        } else {
            // Median of even-count list is average of two middle values.
            // Matches BennyThadikaran reference: avoids outlier bars distorting threshold.
            return (ranges.get(size / 2 - 1) + ranges.get(size / 2)) / 2.0;
        }
    }
}
