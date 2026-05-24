package com.dtech.aitrader.v2.narrative.adx;

import com.dtech.aitrader.v2.narrative.beat.IndicatorComponent;
import com.dtech.aitrader.v2.narrative.engine.IndicatorSeries;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * ADX/DMI(14) computation results. Three components per delta:
 * <ul>
 *   <li>{@code adx} — directional strength (0-100, Wilder-smoothed)</li>
 *   <li>{@code plus_di} — bull directional indicator</li>
 *   <li>{@code minus_di} — bear directional indicator</li>
 * </ul>
 */
@Value
@Builder
public class AdxSeries implements IndicatorSeries {
    double[] adx;
    double[] plusDi;
    double[] minusDi;
    Instant[] barTimestamps;
    String symbol;
    String timeframe;

    @Override
    public int length() {
        return adx.length;
    }

    @Override
    public double[] getComponent(IndicatorComponent component) {
        switch (component) {
            case ADX:      return adx;
            case PLUS_DI:  return plusDi;
            case MINUS_DI: return minusDi;
            default:
                throw new IllegalArgumentException("AdxSeries has no component " + component);
        }
    }
}
