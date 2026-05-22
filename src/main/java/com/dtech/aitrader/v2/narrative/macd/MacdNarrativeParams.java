package com.dtech.aitrader.v2.narrative.macd;

import com.dtech.aitrader.v2.narrative.pivot.SignificanceParams;
import lombok.Builder;
import lombok.Value;

/**
 * Immutable parameters controlling MACD narrative extraction and beat filtering.
 *
 * Configures:
 * - MACD computation periods (fast, slow, signal)
 * - Pivot detection sensitivity via SignificanceParams
 * - Tier window sizes (present, recent, history)
 * - Significance thresholds per tier (for beat survival)
 * - Regime persistence and failed-attempt detection windows
 */
@Value
@Builder
public class MacdNarrativeParams {
    /** Fast EMA period for MACD (default 12). */
    int fastPeriod;

    /** Slow EMA period for MACD (default 26). */
    int slowPeriod;

    /** Signal line EMA period (default 9). */
    int signalPeriod;

    /** Pivot detection params for MACD line and histogram. */
    SignificanceParams pivotParams;

    /** Number of bars from end to classify as PRESENT tier (default 20). */
    int presentWindowBars;

    /** Number of bars from end to classify as RECENT tier (default 100). */
    int recentWindowBars;

    /** Minimum significance for HISTORY tier beats to survive filtering (default 0.85). */
    double historySignificanceMin;

    /** Minimum significance for RECENT tier beats to survive filtering (default 0.6). */
    double recentSignificanceMin;

    /** Minimum significance for PRESENT tier beats to survive filtering (default 0.3). */
    double presentSignificanceMin;

    /** Bars to look ahead from zero_cross to confirm regime persistence (default 5). */
    int regimeChangePersistenceBars;

    /** Window size for failed_attempt detection: reverse must occur within N bars (default 3). */
    int failedAttemptWindowBars;

    /**
     * Factory method returning MacdNarrativeParams with recommended defaults.
     *
     * Defaults:
     * - fastPeriod=12, slowPeriod=26, signalPeriod=9
     * - pivotParams tuned for Phase 1a: atrMult=6.0, pctMin=0.02, hysteresis=0.7, minBarsBetweenPivots=3
     * - presentWindowBars=20, recentWindowBars=72   (weekly: ~15 months recent + 5 months present)
     * - historySignificanceMin=0.85, recentSignificanceMin=0.6, presentSignificanceMin=0.3
     * - regimeChangePersistenceBars=5, failedAttemptWindowBars=3
     *
     * @return MacdNarrativeParams with recommended defaults
     */
    public static MacdNarrativeParams ofDefaults() {
        return MacdNarrativeParams.builder()
                .fastPeriod(12)
                .slowPeriod(26)
                .signalPeriod(9)
                .pivotParams(new SignificanceParams(
                        14,      // atrLength
                        6.0,     // atrMult (Phase 1a tuned)
                        0.02,    // pctMin
                        0.7,     // hysteresis
                        3,       // minBarsBetweenPivots
                        false,   // dynamicPctEnabled
                        1.5,     // volMult
                        20       // rvolWindow
                ))
                .presentWindowBars(20)
                .recentWindowBars(72)
                .historySignificanceMin(0.85)
                .recentSignificanceMin(0.6)
                .presentSignificanceMin(0.3)
                .regimeChangePersistenceBars(5)
                .failedAttemptWindowBars(3)
                .build();
    }
}
