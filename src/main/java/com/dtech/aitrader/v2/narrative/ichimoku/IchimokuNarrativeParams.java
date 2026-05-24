package com.dtech.aitrader.v2.narrative.ichimoku;

import com.dtech.aitrader.v2.narrative.pivot.SignificanceParams;
import lombok.Builder;
import lombok.Value;

/**
 * Ichimoku Kinko Hyo parameters. Canonical (9, 26, 52). SNAPSHOT tier per delta-tier guidance —
 * no narrative, only a state line at the last bar (price vs cloud, TK cross direction, cloud
 * color, future twist).
 */
@Value
@Builder
public class IchimokuNarrativeParams {
    /** Tenkan period — short-term midpoint (default 9). */
    int tenkanPeriod;
    /** Kijun period — medium-term midpoint (default 26). */
    int kijunPeriod;
    /** Senkou B period — long-term midpoint (default 52). */
    int senkouBPeriod;
    /** Forward shift for the cloud (default = kijunPeriod = 26). */
    int displacement;

    SignificanceParams pivotParams;
    int presentWindowBars;
    int recentWindowBars;

    public static IchimokuNarrativeParams ofDefaults() {
        return IchimokuNarrativeParams.builder()
                .tenkanPeriod(9)
                .kijunPeriod(26)
                .senkouBPeriod(52)
                .displacement(26)
                .pivotParams(new SignificanceParams(
                        14, 2.0, 0.05, 0.7, 4, false, 1.5, 20))
                .presentWindowBars(20)
                .recentWindowBars(60)
                .build();
    }
}
