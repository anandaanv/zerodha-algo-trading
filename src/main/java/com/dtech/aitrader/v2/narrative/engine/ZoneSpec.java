package com.dtech.aitrader.v2.narrative.engine;

import com.dtech.aitrader.v2.narrative.beat.IndicatorComponent;
import lombok.Builder;
import lombok.Value;

/**
 * Declares a named zone on an indicator component for {@code entered_zone} / {@code exited_zone}
 * episode beats.
 *
 * <p>For RSI: oversold {@code [0, 30]}, overbought {@code [70, 100]} (default-regime fallback).
 * For Stochastic: oversold {@code [0, 20]}, overbought {@code [80, 100]}. ADX uses zones too:
 * weak-trend {@code [0, 20]}, strong-trend {@code [25, 100]}.
 *
 * <p>The engine emits one {@code entered_zone} beat at the bar where the value crosses INTO the
 * zone, and one {@code exited_zone} beat when it crosses OUT. Duration of stay is recorded on the
 * exit beat as {@code persisted_bars}.
 */
@Value
@Builder
public class ZoneSpec {
    /** Series the zone applies to (e.g. {@link IndicatorComponent#RSI}). */
    IndicatorComponent component;

    /** Human label for the zone ({@code "oversold"}, {@code "overbought"}, {@code "strong_trend"}). */
    String name;

    /** Lower bound (inclusive). */
    double lower;

    /** Upper bound (inclusive). */
    double upper;

    /**
     * Optional minimum bar count required to count as a real zone visit (rather than a one-bar
     * poke). Set to {@code 0} or {@code 1} to emit every entry; higher values filter noise.
     */
    int minPersistenceBars;

    /** Ref-prefix for emitted beats, e.g. {@code "rsi_os_"}. */
    String refPrefix;
}
