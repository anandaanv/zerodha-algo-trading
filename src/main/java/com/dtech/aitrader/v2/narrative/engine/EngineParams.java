package com.dtech.aitrader.v2.narrative.engine;

import com.dtech.aitrader.v2.narrative.pivot.SignificanceParams;
import lombok.Builder;
import lombok.Value;

/**
 * Shared engine parameters used by {@link DescriptiveNarrativeEngine} regardless of indicator.
 *
 * <p>Per-component significance params can be overridden on each {@link PivotComponentSpec}; this
 * class supplies the engine-wide fallback + the tier-window / persistence knobs.
 */
@Value
@Builder
public class EngineParams {
    /** Fallback significance params for any {@link PivotComponentSpec} that does not supply its own. */
    SignificanceParams defaultPivotParams;

    /** Number of bars from end to classify as PRESENT tier. */
    int presentWindowBars;

    /** Number of bars from end to classify as RECENT tier (above which is HISTORY). */
    int recentWindowBars;

    /** Bars a regime must persist to count as regime_change vs failed_attempt. */
    int regimeChangePersistenceBars;

    /**
     * Minimum bars a cross must persist before it's worth emitting as a {@code failed_attempt}.
     * Crosses persisting below this are dropped entirely (non-event noise filter). Per owner
     * memo d3020077 Fix 3 for RSI: a sub-8-week centerline reversal on weekly bars is not a
     * real regime attempt — filtering is honest.
     *
     * <p>Default 0 preserves the original behavior (emit all failed crosses). MACD uses 0
     * (zero-cross attempts are always worth a beat). RSI sets this higher (e.g. 4) so RSI's
     * frequent centerline wobble doesn't clutter the narrative.
     */
    int failedAttemptMinBars;

    /** Per-tier rank caps for the structural pivot verbs. */
    int historyPeakedCap;
    int historyTroughedCap;
    int historyRegimeCap;
    int recentPeakedCap;
    int recentTroughedCap;
    int recentThrustCap;

    /**
     * History-tier cap for {@code entered_zone}/{@code exited_zone} beats. Default 0 means
     * zones don't survive into history (matches the FAST-decay rule from the RSI/Stoch deltas).
     * ADX/EMA-stack regime-conditioner indicators should set this higher — their zone episodes
     * are the indicator's whole point and decay SLOW-MEDIUM per the delta.
     */
    int historyZoneCap;
}
