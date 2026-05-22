package com.dtech.aitrader.v2.narrative.rsi;

import com.dtech.aitrader.v2.narrative.pivot.SignificanceParams;
import lombok.Builder;
import lombok.Value;

/**
 * Immutable parameters controlling RSI narrative extraction. Tuned per the RSI delta
 * (memsys 39d06c2e): split horizon — DEEP regime memory, SHALLOW episode memory.
 */
@Value
@Builder
public class RsiNarrativeParams {

    int period;
    SignificanceParams pivotParams;
    int presentWindowBars;
    int recentWindowBars;
    int regimeChangePersistenceBars;

    /** Oversold zone upper bound (default 30). */
    double oversoldThreshold;

    /** Overbought zone lower bound (default 70). */
    double overboughtThreshold;

    /** Minimum bars an OB/OS visit must last to register a beat (filters one-bar pokes). */
    int zoneMinPersistenceBars;

    /**
     * Window size (in bars) for Brown regime classification. Per delta: "deep regime memory";
     * for weekly bars 26–52 is a reasonable window (~6–12 months).
     */
    int brownRegimeWindowBars;

    /**
     * Median-based Brown regime thresholds. Median(RSI, window) > {@link #brownBullMedianMin}
     * → bull range; median &lt; {@link #brownBearMedianMax} → bear range; else NONE.
     *
     * <p>Permissive heuristic per the governing principle ("imprecision is fine, only honesty
     * matters"). Owner can refine later if the classification feels off.
     */
    double brownBullMedianMin;

    double brownBearMedianMax;

    /** Required persistence (bars) before a new Brown regime is confirmed (kills whipsaw). */
    int brownRegimePersistenceBars;

    public static RsiNarrativeParams ofDefaults() {
        return RsiNarrativeParams.builder()
                .period(14)
                .pivotParams(new SignificanceParams(
                        14,     // atrLength (operates on RSI series, so this is a self-vol surrogate)
                        2.5,    // atrMult — RSI is bounded 0-100, so a smaller multiplier is right
                        0.02,   // pctMin (2% of the RSI value)
                        0.7,    // hysteresis
                        3,      // minBarsBetweenPivots
                        false,  // dynamicPctEnabled
                        1.5,    // volMult
                        20      // rvolWindow
                ))
                .presentWindowBars(20)
                .recentWindowBars(72)
                .regimeChangePersistenceBars(5)
                .oversoldThreshold(30.0)
                .overboughtThreshold(70.0)
                .zoneMinPersistenceBars(2)
                .brownRegimeWindowBars(26)        // ~6 months on weekly bars
                .brownBullMedianMin(55.0)
                .brownBearMedianMax(45.0)
                .brownRegimePersistenceBars(8)    // ~2 months on weekly bars
                .build();
    }
}
