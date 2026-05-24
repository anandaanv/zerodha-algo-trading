package com.dtech.aitrader.v2.narrative.bollinger;

import com.dtech.aitrader.v2.narrative.pivot.SignificanceParams;
import lombok.Builder;
import lombok.Value;

/** Bollinger Bands (20, 2) + ADX(14) context. */
@Value
@Builder
public class BollingerNarrativeParams {
    int period;
    double stdevMult;
    int adxPeriod;

    /** Pivot params for BBW peak/trough detection. */
    SignificanceParams pivotParams;

    int presentWindowBars;
    int recentWindowBars;

    /**
     * Rolling window (bars) over which BBW percentile is computed for the squeeze detector.
     * Owner spec: "squeeze is relative to OWN BBW history" — relative percentile, not absolute.
     */
    int bbwPercentileWindow;

    /** BBW must be in the bottom {@code squeezePctRank} percentile of its window to count as squeeze. */
    double squeezePctRank;

    /** Squeeze must hold ≥ this many bars to register as a real squeeze episode. */
    int squeezeMinPersistenceBars;

    /** Band-walk requires this many consecutive band-tags in the same direction. */
    int bandWalkMinPersistenceBars;

    /** ADX threshold above which a band-tag is contextualized as band-walk (continuation). */
    double bandWalkAdxThreshold;

    /** ADX threshold below which a band-tag is contextualized as mean-reversion (countertrend). */
    double reversionAdxThreshold;

    public static BollingerNarrativeParams ofDefaults() {
        return BollingerNarrativeParams.builder()
                .period(20)
                .stdevMult(2.0)
                .adxPeriod(14)
                .pivotParams(new SignificanceParams(
                        14, 2.5, 0.05, 0.7, 4, false, 1.5, 20))
                .presentWindowBars(20)
                .recentWindowBars(60)
                .bbwPercentileWindow(60)          // ~14 months on weekly — captures recent vol regime
                .squeezePctRank(0.20)             // bottom 20th percentile
                .squeezeMinPersistenceBars(3)
                .bandWalkMinPersistenceBars(4)
                .bandWalkAdxThreshold(25.0)       // delta Section 5
                .reversionAdxThreshold(20.0)      // delta Section 5
                .build();
    }
}
