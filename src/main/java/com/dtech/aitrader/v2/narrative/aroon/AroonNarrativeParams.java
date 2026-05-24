package com.dtech.aitrader.v2.narrative.aroon;

import com.dtech.aitrader.v2.narrative.pivot.SignificanceParams;
import lombok.Builder;
import lombok.Value;

/**
 * Aroon(25) parameters. REGIME_EPISODE tier. EQUIV_CLASS_006 (Trend-Strength) with ADX/EMA-stack
 * — downstream de-dups; engine still emits its own narrative. REGIMEWT_015 (range-detector,
 * INVERSE to trend — thrives in low-ADX environments).
 */
@Value
@Builder
public class AroonNarrativeParams {
    int period;
    SignificanceParams pivotParams;
    int presentWindowBars;
    int recentWindowBars;

    /** Aroon-up ≥ this and Aroon-down ≤ inverse → uptrend regime (default 70). */
    double trendThreshold;
    /** Aroon-up ≤ this and Aroon-down ≤ this → consolidation/no-trend regime (default 50). */
    double consolidationThreshold;

    /** Minimum bars Aroon-up ≥ trendThreshold to count as a trend regime. */
    int regimeMinPersistenceBars;

    public static AroonNarrativeParams ofDefaults() {
        return AroonNarrativeParams.builder()
                .period(25)
                .pivotParams(new SignificanceParams(
                        14, 2.0, 0.05, 0.7, 4, false, 1.5, 20))
                .presentWindowBars(20)
                .recentWindowBars(60)
                .trendThreshold(70.0)
                .consolidationThreshold(50.0)
                .regimeMinPersistenceBars(5)
                .build();
    }
}
