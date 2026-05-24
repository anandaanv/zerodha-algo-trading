package com.dtech.aitrader.v2.narrative.atr;

import com.dtech.aitrader.v2.narrative.pivot.SignificanceParams;
import lombok.Builder;
import lombok.Value;

/**
 * ATR(14) parameters. Pure volatility conditioner — used as a SNAPSHOT (state-line) per delta-tier
 * guidance: emit current ATR + percentile-of-own-range; downstream specialists use it to gate
 * squeeze/breakout patterns. NOT a narrative.
 */
@Value
@Builder
public class AtrNarrativeParams {
    int period;
    SignificanceParams pivotParams;
    int presentWindowBars;
    int recentWindowBars;

    /** Window over which to compute the percentile of the current ATR. */
    int percentileWindow;

    /** ATR percentile ≤ this → "low_vol". Default 0.30. */
    double lowVolPercentile;
    /** ATR percentile ≥ this → "high_vol". Default 0.70. */
    double highVolPercentile;

    public static AtrNarrativeParams ofDefaults() {
        return AtrNarrativeParams.builder()
                .period(14)
                .pivotParams(new SignificanceParams(
                        14, 2.0, 0.05, 0.7, 4, false, 1.5, 20))
                .presentWindowBars(20)
                .recentWindowBars(60)
                .percentileWindow(120)
                .lowVolPercentile(0.30)
                .highVolPercentile(0.70)
                .build();
    }
}
