package com.dtech.aitrader.v2.narrative.atr;

import com.dtech.aitrader.v2.narrative.beat.IndicatorComponent;
import com.dtech.aitrader.v2.narrative.engine.IndicatorSeries;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * ATR(14) computation results. SNAPSHOT tier indicator per Narrative Core 533b3e85 — emitted as a
 * volatility CONDITIONER (percentile of own range), not a narrative.
 */
@Value
@Builder
public class AtrSeries implements IndicatorSeries {
    double[] atr;
    Instant[] barTimestamps;
    String symbol;
    String timeframe;

    @Override
    public int length() {
        return atr.length;
    }

    @Override
    public double[] getComponent(IndicatorComponent component) {
        if (component == IndicatorComponent.ATR) return atr;
        throw new IllegalArgumentException("AtrSeries has no component " + component);
    }
}
