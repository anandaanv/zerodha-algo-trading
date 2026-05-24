package com.dtech.aitrader.v2.narrative.rsi;

import com.dtech.aitrader.v2.narrative.beat.IndicatorComponent;
import com.dtech.aitrader.v2.narrative.engine.IndicatorSeries;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Immutable carrier for RSI computation results. Single component {@code rsi} (RSI(14) by default,
 * Wilder smoothing via ta4j {@link org.ta4j.core.indicators.RSIIndicator}).
 */
@Value
@Builder
public class RsiSeries implements IndicatorSeries {
    double[] rsi;
    Instant[] barTimestamps;
    String symbol;
    String timeframe;

    @Override
    public int length() {
        return rsi.length;
    }

    @Override
    public double[] getComponent(IndicatorComponent component) {
        if (component == IndicatorComponent.RSI) {
            return rsi;
        }
        throw new IllegalArgumentException("RsiSeries has no component " + component);
    }
}
