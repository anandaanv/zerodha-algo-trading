package com.dtech.aitrader.v2.narrative.engine;

import com.dtech.aitrader.v2.narrative.beat.IndicatorComponent;
import lombok.Builder;
import lombok.Value;

/**
 * Declares which indicator component to pair against close-price pivots for divergence detection.
 * Absent ({@code null} in {@code IndicatorConfig.getDivergence()}) means the indicator does not
 * emit divergence beats (ADX, EMA-stack, Bollinger).
 *
 * <p>The engine compares the close price at MACD-style peaks/troughs of the {@code component}
 * series. Bearish/bullish geometry follows the standard rules; later-pivot invalidation marks
 * consequence={@code failed} (Fix 2 from owner validation).
 */
@Value
@Builder
public class DivergenceSpec {
    /** The indicator component whose peaks/troughs drive divergence detection. */
    IndicatorComponent component;

    /**
     * Optional ref-prefix override (e.g. {@code "macd_div_"}). Defaults to {@code "<name>_div_"}
     * if null.
     */
    String refPrefix;
}
