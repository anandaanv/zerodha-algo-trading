package com.dtech.aitrader.v2.narrative.roc;

import com.dtech.aitrader.v2.narrative.pivot.SignificanceParams;
import lombok.Builder;
import lombok.Value;

/**
 * ROC parameters. Horizon: medium per owner (between MACD-long and Stoch-short). Default
 * period=12 (medium momentum). FULL_NARRATIVE tier.
 */
@Value
@Builder
public class RocNarrativeParams {
    int period;
    SignificanceParams pivotParams;
    int presentWindowBars;
    int recentWindowBars;
    int regimeChangePersistenceBars;

    public static RocNarrativeParams ofDefaults() {
        return RocNarrativeParams.builder()
                .period(12)
                .pivotParams(new SignificanceParams(
                        14, 2.0, 0.05, 0.7, 4, false, 1.5, 20))
                .presentWindowBars(20)
                .recentWindowBars(60)
                .regimeChangePersistenceBars(3)
                .build();
    }
}
