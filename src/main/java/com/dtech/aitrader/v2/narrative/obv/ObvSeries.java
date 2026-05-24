package com.dtech.aitrader.v2.narrative.obv;

import com.dtech.aitrader.v2.narrative.beat.IndicatorComponent;
import com.dtech.aitrader.v2.narrative.engine.IndicatorSeries;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * OBV (On-Balance Volume) cumulative series. FULL_NARRATIVE tier per Narrative Core 533b3e85 —
 * but the engine emits its DIVERGENCE primarily; raw OBV value is not interesting (it's
 * cumulative, scale-dependent). Owner guidance (b3ff4ca0): "volume-based. DIVERGENCE is its whole
 * point (price up + OBV flat/down = distribution). Volume-confirmation overlay on breakouts.
 * EQUIV_CLASS_009 (Volume-Confirmation-Breakout)."
 */
@Value
@Builder
public class ObvSeries implements IndicatorSeries {
    double[] obv;
    Instant[] barTimestamps;
    String symbol;
    String timeframe;

    @Override
    public int length() {
        return obv.length;
    }

    @Override
    public double[] getComponent(IndicatorComponent component) {
        if (component == IndicatorComponent.OBV) return obv;
        throw new IllegalArgumentException("ObvSeries has no component " + component);
    }
}
