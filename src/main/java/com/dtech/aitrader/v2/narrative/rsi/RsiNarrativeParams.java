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

    /** OS zone upper bound when Brown regime is transitioning/none (fallback to absolute). */
    double oversoldThreshold;

    /** OB zone lower bound when Brown regime is transitioning/none (fallback to absolute). */
    double overboughtThreshold;

    /**
     * BULL-regime "oversold" zone upper bound. Per Brown: bull-range support is ~40-50; a dip
     * to ≤ this is the bull-regime OS event (continuation-buy setup).
     */
    double bullRegimeOsUpper;

    /**
     * BULL-regime "overbought" zone lower bound. Bull-range resistance is around ~90; OB events
     * here are momentum-extension signals, not reversal.
     */
    double bullRegimeObLower;

    /**
     * BEAR-regime "oversold" zone upper bound. Bear-range floor is ~20; visits below this are
     * deeply oversold even for the bear regime.
     */
    double bearRegimeOsUpper;

    /**
     * BEAR-regime "overbought" zone lower bound. Bear-range resistance is ~55-65; a visit here
     * is the bear-regime OB event (continuation-sell setup).
     */
    double bearRegimeObLower;

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

    /**
     * Maximum bars from the P3 pivot to the confirming "break" for a Wilder failure swing.
     * Wilder's pattern is short-term confirmation; delayed breaks (months later) are usually
     * different setups, not a continuation of the failure-swing thesis. Default ~15 weekly bars
     * (~3-4 months).
     */
    int failureSwingMaxBreakWindow;

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
                // FIX 3 (owner d3020077): raised from 5 to 8 — a sub-8-week reversal on a
                // weekly chart isn't a real centerline regime attempt, so filtering is honest.
                .regimeChangePersistenceBars(8)
                .oversoldThreshold(30.0)
                .overboughtThreshold(70.0)
                .bullRegimeOsUpper(50.0)    // Brown bull-range support ~40-50
                .bullRegimeObLower(80.0)    // Bull-range resistance ~90 (use 80 to catch the approach)
                .bearRegimeOsUpper(30.0)    // Bear-range floor ~20 (use 30 to catch the approach)
                .bearRegimeObLower(60.0)    // Bear-range resistance ~55-65
                .zoneMinPersistenceBars(2)
                .brownRegimeWindowBars(26)        // ~6 months on weekly bars
                .brownBullMedianMin(55.0)
                .brownBearMedianMax(45.0)
                .brownRegimePersistenceBars(8)    // ~2 months on weekly bars
                .failureSwingMaxBreakWindow(15)   // ~3.5 months on weekly bars
                .build();
    }
}
