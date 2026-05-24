package com.dtech.aitrader.v2.narrative.engine;

import com.dtech.aitrader.v2.narrative.beat.IndicatorComponent;

import java.time.Instant;

/**
 * Read-only carrier for an indicator's computed components (one or more numeric series),
 * shared timestamp axis, and identifying metadata.
 *
 * <p>Different indicators expose different components: MACD has {@code macd_line}, {@code signal},
 * {@code histogram}; RSI has just {@code rsi}; Stochastic has {@code stoch_k} + {@code stoch_d}.
 * The engine reads components by name via {@link #getComponent(IndicatorComponent)}.
 *
 * <p>All series MUST share the same length and the same timestamp axis ({@link #getBarTimestamps()}).
 */
public interface IndicatorSeries {

    /** Number of bars in every component series. */
    int length();

    /**
     * Numeric values for a component. Caller must not mutate the returned array.
     *
     * @throws IllegalArgumentException if the component is not present in this series.
     */
    double[] getComponent(IndicatorComponent component);

    /** Timestamps parallel to every component series. Length equals {@link #length()}. */
    Instant[] getBarTimestamps();

    /** Trading symbol (e.g., "RELIANCE"). */
    String getSymbol();

    /** Timeframe label (e.g., "Week"). */
    String getTimeframe();
}
