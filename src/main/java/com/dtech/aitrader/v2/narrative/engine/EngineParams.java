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

    /** Per-tier rank caps for the structural pivot verbs. */
    int historyPeakedCap;
    int historyTroughedCap;
    int historyRegimeCap;
    int recentPeakedCap;
    int recentTroughedCap;
    int recentThrustCap;
}
