package com.dtech.aitrader.v2.narrative.ichimoku;

import com.dtech.aitrader.v2.narrative.beat.IndicatorComponent;
import com.dtech.aitrader.v2.narrative.engine.IndicatorSeries;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Ichimoku Kinko Hyo components. SNAPSHOT tier per Narrative Core 533b3e85 — emitted as a
 * positional/boolean state (price vs cloud, TK cross, cloud color, future twist). REGIMEWT_012
 * (cloud as integrated regime).
 *
 * <p>Components: Tenkan-sen (9), Kijun-sen (26), Senkou Span A (midpoint forward-shifted),
 * Senkou Span B (52 forward-shifted), Chikou Span (close back-shifted 26).
 *
 * <p>{@link #closes} carried so the state-line at last bar can report price-vs-cloud without
 * re-walking OHLC.
 */
@Value
@Builder
public class IchimokuSeries implements IndicatorSeries {
    double[] tenkan;
    double[] kijun;
    /** Senkou A = (tenkan + kijun) / 2, shifted forward 26. ALIGNED — at index i we store the
     *  Senkou A value FOR bar i (which is the (tenkan+kijun)/2 from bar i-26). */
    double[] senkouA;
    /** Senkou B = midpoint of 52-bar H/L, shifted forward 26. ALIGNED same way. */
    double[] senkouB;
    /** Chikou Span = close back-shifted 26 — at index i it's bars[i+26].close, or NaN at the tail. */
    double[] chikou;
    double[] closes;
    Instant[] barTimestamps;
    String symbol;
    String timeframe;

    @Override
    public int length() {
        return tenkan.length;
    }

    @Override
    public double[] getComponent(IndicatorComponent component) {
        switch (component) {
            case ICHIMOKU_TENKAN: return tenkan;
            case ICHIMOKU_KIJUN: return kijun;
            case ICHIMOKU_SENKOU_A: return senkouA;
            case ICHIMOKU_SENKOU_B: return senkouB;
            case ICHIMOKU_CHIKOU: return chikou;
            default:
                throw new IllegalArgumentException("IchimokuSeries has no component " + component);
        }
    }
}
