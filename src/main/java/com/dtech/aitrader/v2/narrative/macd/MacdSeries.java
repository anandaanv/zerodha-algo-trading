package com.dtech.aitrader.v2.narrative.macd;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Immutable carrier for MACD computation results.
 *
 * Contains the MACD line, signal line, histogram, and associated metadata
 * (bar indices, timestamps, symbol, timeframe) for downstream processing.
 */
@Value
@Builder
public class MacdSeries {
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
}
