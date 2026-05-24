package com.dtech.aitrader.v2.narrative.donchian;

import com.dtech.aitrader.v2.narrative.beat.IndicatorComponent;
import com.dtech.aitrader.v2.narrative.engine.IndicatorSeries;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Donchian Channels(20) components. REGIME_EPISODE tier per Narrative Core 533b3e85. The
 * narrative is channel breakouts (event) + channel-width compression episodes. Equivalence
 * class: EQUIV_CLASS_004-breakout-ish / EQUIV_CLASS_009 with volume.
 */
@Value
@Builder
public class DonchianSeries implements IndicatorSeries {
    double[] upper;
    double[] lower;
    double[] middle;
    double[] width;     // (upper - lower) / middle × 100
    double[] closes;
    Instant[] barTimestamps;
    String symbol;
    String timeframe;

    @Override
    public int length() {
        return upper.length;
    }

    @Override
    public double[] getComponent(IndicatorComponent component) {
        switch (component) {
            case DONCHIAN_UPPER: return upper;
            case DONCHIAN_LOWER: return lower;
            case DONCHIAN_MIDDLE: return middle;
            case DONCHIAN_WIDTH: return width;
            default:
                throw new IllegalArgumentException("DonchianSeries has no component " + component);
        }
    }
}
