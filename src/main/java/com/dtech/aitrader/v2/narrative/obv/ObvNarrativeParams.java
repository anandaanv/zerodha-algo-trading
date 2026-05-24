package com.dtech.aitrader.v2.narrative.obv;

import com.dtech.aitrader.v2.narrative.pivot.SignificanceParams;
import lombok.Builder;
import lombok.Value;

/**
 * OBV parameters. FULL_NARRATIVE tier — divergence-dominant; no thrust verb (OBV is cumulative,
 * thrust comparison is meaningless across instruments). Horizon: medium-long (volume divergence
 * builds slowly).
 */
@Value
@Builder
public class ObvNarrativeParams {
    SignificanceParams pivotParams;
    int presentWindowBars;
    int recentWindowBars;

    public static ObvNarrativeParams ofDefaults() {
        return ObvNarrativeParams.builder()
                // OBV swings are scale-dependent; the relative-significance engine handles this.
                .pivotParams(new SignificanceParams(
                        14, 2.0, 0.05, 0.7, 4, false, 1.5, 20))
                .presentWindowBars(20)
                .recentWindowBars(80)
                .build();
    }
}
