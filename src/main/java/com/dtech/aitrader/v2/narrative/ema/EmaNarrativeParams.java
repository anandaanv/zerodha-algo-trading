package com.dtech.aitrader.v2.narrative.ema;

import com.dtech.aitrader.v2.narrative.pivot.SignificanceParams;
import lombok.Builder;
import lombok.Value;

/**
 * EMA-stack parameters per delta 417f755d. SPLIT-horizon: deep stack-regime memory, shallow
 * pullback memory. Regime conditioner like ADX.
 */
@Value
@Builder
public class EmaNarrativeParams {
    int p20;
    int p50;
    int p100;
    int p200;

    /** Pivot params for any structural-pivot detection — unused for EMA but kept for engine API. */
    SignificanceParams pivotParams;

    int presentWindowBars;
    int recentWindowBars;

    /** Minimum bars a stack-alignment state must HOLD to count as a regime. */
    int stackRegimeMinPersistenceBars;

    /**
     * Window (in bars) within which fast cross (20/50) + golden cross (50/200) + full-stack
     * alignment collapse into ONE composite regime-birth beat (lifecycle collapse per delta
     * Section 6 / KB INTERACTION_018). Default ~30 bars per delta.
     */
    int lifecycleCollapseWindowBars;

    /** % distance threshold for "price near MA" detection (pullback). E.g. 0.02 = within 2% of EMA50. */
    double pullbackProximityPct;

    /** Reference MA for pullback detection (typically EMA50 in an established stack). */
    int pullbackReferenceMa;

    public static EmaNarrativeParams ofDefaults() {
        return EmaNarrativeParams.builder()
                .p20(20).p50(50).p100(100).p200(200)
                .pivotParams(new SignificanceParams(
                        14, 2.0, 0.02, 0.7, 3, false, 1.5, 20))
                .presentWindowBars(20)
                .recentWindowBars(72)
                .stackRegimeMinPersistenceBars(5)   // ~5 weekly bars before stack regime confirmed
                .lifecycleCollapseWindowBars(30)
                .pullbackProximityPct(0.03)          // within 3% of EMA50 = "at" the MA
                .pullbackReferenceMa(50)
                .build();
    }
}
