package com.dtech.aitrader.v2.narrative.stochrsi;

import com.dtech.aitrader.v2.narrative.beat.IndicatorComponent;
import com.dtech.aitrader.v2.narrative.engine.IndicatorSeries;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * StochRSI(14,14,3,3) computation results. Scaled to 0-100 (same as RSI/Stoch) for readability.
 * Two components:
 * <ul>
 *   <li>{@code stochrsi_k} — Slow %K = SMA(raw StochRSI, 3) * 100</li>
 *   <li>{@code stochrsi_d} — Slow %D = SMA(Slow %K, 3)</li>
 * </ul>
 */
@Value
@Builder
public class StochRsiSeries implements IndicatorSeries {
    double[] stochrsiK;
    double[] stochrsiD;
    Instant[] barTimestamps;
    String symbol;
    String timeframe;

    @Override
    public int length() {
        return stochrsiK.length;
    }

    @Override
    public double[] getComponent(IndicatorComponent component) {
        switch (component) {
            case STOCHRSI_K: return stochrsiK;
            case STOCHRSI_D: return stochrsiD;
            default:
                throw new IllegalArgumentException("StochRsiSeries has no component " + component);
        }
    }
}
