package com.dtech.aitrader.v2.narrative.ema;

import com.dtech.aitrader.v2.narrative.beat.IndicatorComponent;
import com.dtech.aitrader.v2.narrative.engine.IndicatorSeries;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * 4-tier EMA stack (20/50/100/200) — Standard EMA, not Wilder per delta 417f755d Section 2.
 */
@Value
@Builder
public class EmaSeries implements IndicatorSeries {
    double[] ema20;
    double[] ema50;
    double[] ema100;
    double[] ema200;
    double[] closes; // price for price-vs-MA interactions and pullback detection
    Instant[] barTimestamps;
    String symbol;
    String timeframe;

    @Override
    public int length() {
        return ema20.length;
    }

    @Override
    public double[] getComponent(IndicatorComponent component) {
        switch (component) {
            case EMA20:  return ema20;
            case EMA50:  return ema50;
            case EMA100: return ema100;
            case EMA200: return ema200;
            default:
                throw new IllegalArgumentException("EmaSeries has no component " + component);
        }
    }
}
