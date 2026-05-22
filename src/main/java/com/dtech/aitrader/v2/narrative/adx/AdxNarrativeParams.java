package com.dtech.aitrader.v2.narrative.adx;

import com.dtech.aitrader.v2.narrative.pivot.SignificanceParams;
import lombok.Builder;
import lombok.Value;

/**
 * ADX/DMI(14) parameters per delta e94519ea. PERSISTENCE-DOMINANT noise filter — owner labels
 * ADX "the primary whipsaw stress test of the whole project."
 */
@Value
@Builder
public class AdxNarrativeParams {
    int period;
    SignificanceParams pivotParams;
    int presentWindowBars;
    int recentWindowBars;

    /** ADX > strongTrendThreshold means trending regime (default 25). */
    double strongTrendThreshold;
    /** ADX < rangeThreshold means range regime (default 20). 20-25 transitional. */
    double rangeThreshold;

    /**
     * Minimum bars ADX must HOLD above/below threshold before a regime entry/exit counts. The
     * single most important ADX filter per delta Section 10. Spec says "4-5 weekly bars" —
     * picking 5 as the safer default.
     */
    int regimeMinPersistenceBars;

    /**
     * DI crosses are SUPPRESSED when ADX < this value. Per delta Section 10 Wilder caveat: low
     * ADX = no real trend, DI crosses there are noise.
     */
    double diCrossMinAdx;

    /** Bars to look around an ADX-25 cross for a DI cross to form the composite "trend initiation" beat. */
    int trendInitiationWindowBars;

    public static AdxNarrativeParams ofDefaults() {
        return AdxNarrativeParams.builder()
                .period(14)
                .pivotParams(new SignificanceParams(
                        14, 2.0, 0.05, 0.7, 4, false, 1.5, 20))
                .presentWindowBars(20)
                .recentWindowBars(60)
                .strongTrendThreshold(25.0)
                .rangeThreshold(20.0)
                .regimeMinPersistenceBars(5)        // owner: "persistence ≥ 4-5"
                .diCrossMinAdx(20.0)                // suppress DI crosses below this
                .trendInitiationWindowBars(3)       // ±3 bars per delta Section 6
                .build();
    }
}
