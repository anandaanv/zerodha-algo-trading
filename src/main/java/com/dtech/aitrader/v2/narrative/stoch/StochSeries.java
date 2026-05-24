package com.dtech.aitrader.v2.narrative.stoch;

import com.dtech.aitrader.v2.narrative.beat.IndicatorComponent;
import com.dtech.aitrader.v2.narrative.engine.IndicatorSeries;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Slow Stochastic computation results. Two components:
 * <ul>
 *   <li>{@code stoch_k} — Slow %K = SMA(Fast %K, 3) per Lane's canonical default</li>
 *   <li>{@code stoch_d} — Slow %D = SMA(Slow %K, 3)</li>
 * </ul>
 */
@Value
@Builder
public class StochSeries implements IndicatorSeries {
    double[] stochK;
    double[] stochD;
    Instant[] barTimestamps;
    String symbol;
    String timeframe;

    @Override
    public int length() {
        return stochK.length;
    }

    @Override
    public double[] getComponent(IndicatorComponent component) {
        switch (component) {
            case STOCH_K: return stochK;
            case STOCH_D: return stochD;
            default:
                throw new IllegalArgumentException("StochSeries has no component " + component);
        }
    }
}
