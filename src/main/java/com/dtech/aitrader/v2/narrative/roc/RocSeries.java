package com.dtech.aitrader.v2.narrative.roc;

import com.dtech.aitrader.v2.narrative.beat.IndicatorComponent;
import com.dtech.aitrader.v2.narrative.engine.IndicatorSeries;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * ROC(period) momentum series. Single component {@code roc} = 100 × (close[i] − close[i−period])
 * / close[i−period]. Unbounded (can be very large in either direction). FULL_NARRATIVE tier per
 * Narrative Core 533b3e85 — divergence + zero-line regime + thrust (like MACD but single-line).
 * EQUIV_CLASS_003/004 (divergence classes).
 */
@Value
@Builder
public class RocSeries implements IndicatorSeries {
    double[] roc;
    Instant[] barTimestamps;
    String symbol;
    String timeframe;

    @Override
    public int length() {
        return roc.length;
    }

    @Override
    public double[] getComponent(IndicatorComponent component) {
        if (component == IndicatorComponent.ROC) return roc;
        throw new IllegalArgumentException("RocSeries has no component " + component);
    }
}
