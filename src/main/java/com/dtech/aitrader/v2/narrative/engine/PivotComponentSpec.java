package com.dtech.aitrader.v2.narrative.engine;

import com.dtech.aitrader.v2.narrative.beat.BeatVerb;
import com.dtech.aitrader.v2.narrative.beat.IndicatorComponent;
import com.dtech.aitrader.v2.narrative.pivot.SignificanceParams;
import lombok.Builder;
import lombok.Value;

/**
 * Declares one component on which the engine runs adaptive-significance pivot detection,
 * plus the verb to emit at each pivot.
 *
 * <p>For MACD: {@code (macd_line, PEAKED/TROUGHED)} and {@code (histogram, THRUST)}.
 * For RSI: {@code (rsi, PEAKED/TROUGHED)}.
 * For ADX: {@code (adx, PEAKED/TROUGHED)} (acts like thrust internally but emitted via the
 * regime-episode pathway, not the structural-thrust pathway).
 */
@Value
@Builder
public class PivotComponentSpec {
    /** Which series to detect pivots on. */
    IndicatorComponent component;

    /**
     * Verb to emit at each detected pivot. Currently one of:
     * <ul>
     *   <li>{@link BeatVerb#PEAKED}/{@link BeatVerb#TROUGHED} pair — emit PEAKED at peaks,
     *       TROUGHED at troughs. (Pass either; engine emits both based on pivot kind.)</li>
     *   <li>{@link BeatVerb#THRUST} — emit THRUST at both peaks and troughs (the MACD-histogram
     *       case; one verb whose direction is implicit in the value sign).</li>
     * </ul>
     */
    BeatVerb verb;

    /**
     * Significance params for this component. If null, the engine falls back to its
     * shared {@code params.pivotParams()} default.
     */
    SignificanceParams significanceParams;

    /** Optional ref-prefix override (e.g. {@code "macd_pk_"} vs {@code "rsi_pk_"}). */
    String refPrefix;

    /** Optional note template prefix (e.g. {@code "MACD"} vs {@code "RSI"}). */
    String labelPrefix;
}
