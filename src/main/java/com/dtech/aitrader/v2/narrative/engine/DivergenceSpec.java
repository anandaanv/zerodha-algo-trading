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
     * Component label to put on emitted divergence beats. Conventionally an "<indicator>_all"
     * value (e.g. {@code MACD_ALL} for MACD, {@code RSI_ALL} for RSI) to reflect that the beat
     * spans the whole indicator. Defaults to {@link #component} if null.
     */
    IndicatorComponent beatComponent;

    /**
     * Optional ref-prefix override (e.g. {@code "macd_div_"}). Defaults to {@code "<name>_div_"}
     * if null.
     */
    String refPrefix;

    /**
     * Label used in the beat's {@code note} field for the component being compared against price
     * (e.g. "MACD", "RSI"). Defaults to "indicator" if null.
     */
    String componentLabel;
}
