package com.dtech.aitrader.v2.narrative.keltner;

import com.dtech.aitrader.v2.narrative.pivot.SignificanceParams;
import lombok.Builder;
import lombok.Value;

/**
 * Keltner Channels parameters. REGIME_EPISODE tier. Same squeeze-then-walk narrative as Bollinger
 * but ATR-based; squeeze relative to OWN channel-width history (never absolute). Default (20, 2×ATR(10)).
 */
@Value
@Builder
public class KeltnerNarrativeParams {
    int period;          // EMA period for middle band (default 20)
    int atrPeriod;       // ATR period (default 10)
    double atrMult;      // ATR multiplier for outer bands (default 2.0)
    int adxPeriod;       // ADX period for band-walk disambiguation (default 14)
    SignificanceParams pivotParams;
    int presentWindowBars;
    int recentWindowBars;

    /** Width percentile window for squeeze detection. */
    int widthPercentileWindow;
    /** Width below this percentile of its own history → squeeze (default 0.20). */
    double squeezePctRank;
    /** Bars a squeeze must hold to count as an episode. */
    int squeezeMinPersistenceBars;

    /** ADX ≥ this → band-walk continuation interpretation (default 25). */
    double bandWalkAdxThreshold;
    /** ADX ≤ this → mean-reversion interpretation (default 20). */
    double reversionAdxThreshold;
    /** Minimum bars price must ride a band to count as a band-walk regime. */
    int bandWalkMinPersistenceBars;

    public static KeltnerNarrativeParams ofDefaults() {
        return KeltnerNarrativeParams.builder()
                .period(20)
                .atrPeriod(10)
                .atrMult(2.0)
                .adxPeriod(14)
                .pivotParams(new SignificanceParams(
                        14, 2.0, 0.05, 0.7, 4, false, 1.5, 20))
                .presentWindowBars(20)
                .recentWindowBars(60)
                .widthPercentileWindow(120)
                .squeezePctRank(0.20)
                .squeezeMinPersistenceBars(3)
                .bandWalkAdxThreshold(25.0)
                .reversionAdxThreshold(20.0)
                .bandWalkMinPersistenceBars(3)
                .build();
    }
}
