package com.dtech.aitrader.v2.narrative.bollinger;

import com.dtech.aitrader.v2.narrative.beat.IndicatorComponent;
import com.dtech.aitrader.v2.narrative.engine.IndicatorSeries;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Bollinger Bands (20, 2) computation results.
 * <ul>
 *   <li>{@code bb_middle} — SMA(close, 20)</li>
 *   <li>{@code bb_upper} — middle + 2·stdev(close, 20)</li>
 *   <li>{@code bb_lower} — middle − 2·stdev(close, 20)</li>
 *   <li>{@code bb_width} — (upper − lower)/middle × 100</li>
 *   <li>{@code bb_percent} — (close − lower)/(upper − lower)</li>
 * </ul>
 *
 * <p>Also carries the close-price series and a precomputed ADX series (for band-tag regime
 * disambiguation per delta Section 5). ADX is not exposed via {@link #getComponent} — it's an
 * indicator-internal aid that {@link BollingerIndicatorConfig} reads directly.
 */
@Value
@Builder
public class BollingerSeries implements IndicatorSeries {
    double[] middle;
    double[] upper;
    double[] lower;
    double[] width;
    double[] percentB;
    double[] closes;
    double[] adx;        // for band-tag regime disambiguation
    Instant[] barTimestamps;
    String symbol;
    String timeframe;

    @Override
    public int length() {
        return middle.length;
    }

    @Override
    public double[] getComponent(IndicatorComponent component) {
        switch (component) {
            case BB_MIDDLE:  return middle;
            case BB_UPPER:   return upper;
            case BB_LOWER:   return lower;
            case BB_WIDTH:   return width;
            case BB_PERCENT: return percentB;
            default:
                throw new IllegalArgumentException("BollingerSeries has no component " + component);
        }
    }
}
