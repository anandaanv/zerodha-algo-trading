package com.dtech.aitrader.v2.narrative.donchian;

import com.dtech.aitrader.v2.narrative.pivot.SignificanceParams;
import lombok.Builder;
import lombok.Value;

/**
 * Donchian Channels parameters. Channel = highest-high / lowest-low over {@code period}. Default
 * 20 (Turtle Trading classic). REGIME_EPISODE tier. EQUIV_CLASS_004 (breakout) — interacts with
 * volume confirmation (EQUIV_CLASS_009) downstream.
 */
@Value
@Builder
public class DonchianNarrativeParams {
    int period;
    SignificanceParams pivotParams;
    int presentWindowBars;
    int recentWindowBars;

    /** Width percentile window for compression episode detection. */
    int widthPercentileWindow;
    /** Width ≤ this percentile of own history → compression (default 0.20). */
    double compressionPctRank;
    /** Bars compression must hold to count as an episode. */
    int compressionMinPersistenceBars;

    public static DonchianNarrativeParams ofDefaults() {
        return DonchianNarrativeParams.builder()
                .period(20)
                .pivotParams(new SignificanceParams(
                        14, 2.0, 0.05, 0.7, 4, false, 1.5, 20))
                .presentWindowBars(20)
                .recentWindowBars(60)
                .widthPercentileWindow(120)
                .compressionPctRank(0.20)
                .compressionMinPersistenceBars(3)
                .build();
    }
}
