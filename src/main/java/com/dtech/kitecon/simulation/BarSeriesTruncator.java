package com.dtech.kitecon.simulation;

import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import java.time.Instant;

public class BarSeriesTruncator {
    /**
     * Returns a new BarSeries containing only bars with endTime <= cutoff.
     */
    public static BarSeries truncate(BarSeries full, Instant cutoff) {
        BarSeries truncated = new BaseBarSeriesBuilder().withName(full.getName() + "_sim").build();
        for (int i = 0; i <= full.getEndIndex(); i++) {
            Bar bar = full.getBar(i);
            if (!bar.getEndTime().isAfter(cutoff)) {
                truncated.addBar(bar);
            }
        }
        return truncated;
    }
}
