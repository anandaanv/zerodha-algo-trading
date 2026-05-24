package com.dtech.aitrader.v2.narrative.macd;

import com.dtech.aitrader.v2.narrative.beat.IndicatorComponent;
import com.dtech.aitrader.v2.narrative.engine.IndicatorSeries;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Immutable carrier for MACD computation results.
 *
 * Implements the engine's {@link IndicatorSeries} contract so the generic narrative engine
 * can read MACD components by name without knowing it is MACD.
 */
@Value
@Builder
public class MacdSeries implements IndicatorSeries {
    /** MACD line values: fastEma - slowEma */
    double[] macdLine;

    /** Signal line values: EMA of MACD */
    double[] signalLine;

    /** Histogram values: MACD - Signal */
    double[] histogram;

    /** Bar indices [0, 1, 2, ...] for tracking position in input series */
    int[] barIndices;

    /** Bar timestamps converted to Instant, parallel to MACD arrays */
    Instant[] barTimestamps;

    /** Trading symbol (e.g., "RELIANCE") */
    String symbol;

    /** Timeframe (e.g., "Week") */
    String timeframe;

    @Override
    public int length() {
        return macdLine.length;
    }

    @Override
    public double[] getComponent(IndicatorComponent component) {
        switch (component) {
            case MACD_LINE:
                return macdLine;
            case SIGNAL_LINE:
                return signalLine;
            case HISTOGRAM:
                return histogram;
            default:
                throw new IllegalArgumentException("MacdSeries has no component " + component);
        }
    }
}
