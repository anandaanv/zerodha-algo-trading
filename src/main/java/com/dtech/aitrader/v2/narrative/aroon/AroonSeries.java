package com.dtech.aitrader.v2.narrative.aroon;

import com.dtech.aitrader.v2.narrative.beat.IndicatorComponent;
import com.dtech.aitrader.v2.narrative.engine.IndicatorSeries;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Aroon(25) components. REGIME_EPISODE tier per Narrative Core 533b3e85 — mirror of ADX as a
 * trend/range classifier (EQUIV_CLASS_006 Trend-Strength with ADX + EMA-stack). Aroon-up and
 * Aroon-down are each on [0, 100]; the oscillator is the difference, on [-100, +100].
 */
@Value
@Builder
public class AroonSeries implements IndicatorSeries {
    double[] aroonUp;
    double[] aroonDown;
    double[] aroonOsc;
    Instant[] barTimestamps;
    String symbol;
    String timeframe;

    @Override
    public int length() {
        return aroonUp.length;
    }

    @Override
    public double[] getComponent(IndicatorComponent component) {
        switch (component) {
            case AROON_UP: return aroonUp;
            case AROON_DOWN: return aroonDown;
            case AROON_OSC: return aroonOsc;
            default:
                throw new IllegalArgumentException("AroonSeries has no component " + component);
        }
    }
}
