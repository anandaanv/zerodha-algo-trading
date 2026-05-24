package com.dtech.aitrader.v2.narrative.vwap;

import com.dtech.aitrader.v2.narrative.pivot.SignificanceParams;
import lombok.Builder;
import lombok.Value;

/**
 * VWAP parameters. Rolling-window VWAP (period-based) — session-anchored AVWAP is a later
 * refinement. Defaults: 20-bar rolling. REGIMEWT_013 (anchor decay).
 */
@Value
@Builder
public class VwapNarrativeParams {
    int period;
    SignificanceParams pivotParams;
    int presentWindowBars;
    int recentWindowBars;

    /** Distance threshold in % of price beyond which "well above/below VWAP" is flagged. */
    double materialDistancePct;

    public static VwapNarrativeParams ofDefaults() {
        return VwapNarrativeParams.builder()
                .period(20)
                .pivotParams(new SignificanceParams(
                        14, 2.0, 0.05, 0.7, 4, false, 1.5, 20))
                .presentWindowBars(20)
                .recentWindowBars(60)
                .materialDistancePct(1.0)
                .build();
    }
}
