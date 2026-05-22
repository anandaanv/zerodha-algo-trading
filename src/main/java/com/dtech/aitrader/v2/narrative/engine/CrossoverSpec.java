package com.dtech.aitrader.v2.narrative.engine;

import com.dtech.aitrader.v2.narrative.beat.IndicatorComponent;
import lombok.Builder;
import lombok.Value;

/**
 * Declares a crossover detection rule between two components, or between one component and a
 * fixed level (e.g. zero, centerline, threshold).
 *
 * <p>Examples:
 * <ul>
 *   <li>MACD zero-cross: {@code primary=macd_line, kind=VS_LEVEL, level=0.0, regimeRelevant=true}</li>
 *   <li>MACD signal-cross: {@code primary=macd_line, reference=signal_line, kind=VS_LINE, regimeRelevant=false}</li>
 *   <li>RSI centerline-cross: {@code primary=rsi, kind=VS_LEVEL, level=50, regimeRelevant=true}</li>
 *   <li>DI cross (ADX): {@code primary=plus_di, reference=minus_di, kind=VS_LINE, regimeRelevant=true}</li>
 * </ul>
 *
 * <p>When {@link #isRegimeRelevant()} is true, the engine applies the regime-change / failed-attempt
 * classification (one cross → one verb based on persistence). When false, the cross emits as a bare
 * {@code CROSSED} beat which the tier filter drops by default.
 */
@Value
@Builder
public class CrossoverSpec {

    public enum Kind {
        /** Cross between two indicator component series. */
        VS_LINE,
        /** Cross between a component series and a fixed numeric level. */
        VS_LEVEL
    }

    /** The component that does the crossing. */
    IndicatorComponent primary;

    /** Only set when {@link #kind} == {@link Kind#VS_LINE}. */
    IndicatorComponent reference;

    /** Only set when {@link #kind} == {@link Kind#VS_LEVEL}. */
    Double level;

    Kind kind;

    /**
     * If true, the engine applies the regime_change / failed_attempt classification
     * (one-verb-per-cross by persistence). If false, the engine emits a bare CROSSED beat
     * (which tier filtering generally drops).
     */
    boolean regimeRelevant;

    /** Label used for the "from"/"to" fields on bare CROSSED beats (e.g. "above_zero", "above_signal"). */
    String aboveLabel;

    String belowLabel;
}
