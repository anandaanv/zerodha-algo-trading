package com.dtech.aitrader.v2.narrative.vwap;

import com.dtech.aitrader.v2.narrative.beat.IndicatorComponent;
import com.dtech.aitrader.v2.narrative.engine.IndicatorSeries;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Rolling VWAP computation results. SNAPSHOT tier per Narrative Core 533b3e85 — emitted as a
 * positional/boolean state (above/below VWAP + distance). VWAP is anchored (resets at session
 * boundaries on intraday TFs); on daily/weekly it degenerates to close ≈ VWAP and the state-line
 * carries that fact.
 *
 * <p>{@link #closes} kept on the series so {@link VwapIndicatorConfig#buildCurrentlyBeat} can
 * report distance-from-vwap in % at the last bar without re-walking the OHLC list.
 */
@Value
@Builder
public class VwapSeries implements IndicatorSeries {
    double[] vwap;
    double[] closes;
    Instant[] barTimestamps;
    String symbol;
    String timeframe;

    @Override
    public int length() {
        return vwap.length;
    }

    @Override
    public double[] getComponent(IndicatorComponent component) {
        if (component == IndicatorComponent.VWAP) return vwap;
        throw new IllegalArgumentException("VwapSeries has no component " + component);
    }
}
