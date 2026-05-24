package com.dtech.aitrader.v2.narrative.pivot;

import java.util.List;

/**
 * Service interface for detecting significant pivots (peaks and troughs) in a plain numeric series.
 *
 * This engine is series-general: it operates on any 1D numeric array without OHLC bars or timestamps.
 * Suitable for detecting pivots in indicator lines (MACD line/histogram, RSI, derivatives, etc.)
 * as well as price series.
 *
 * Ported from ZigZagService but simplified for indicator data:
 * - No high/low pairs per bar; single value per index.
 * - No timestamps or bar metadata.
 * - No retracement/extension percentage computation.
 * - Algorithm identical to ZigZagService except for the 1D adaptation.
 */
public interface SeriesSignificantPivotEngine {
    /**
     * Detect all significant pivots in a numeric series.
     *
     * @param series numeric array, oldest-first (series[0] is the earliest bar)
     * @param params algorithm parameters controlling sensitivity, thresholds, hysteresis, etc.
     * @return list of SeriesPivot objects, ordered by index (earliest first).
     *         Returns empty list if series is empty or null, or if no pivots are detected.
     *
     * @throws IllegalArgumentException if params contains invalid values
     */
    List<SeriesPivot> detect(double[] series, SignificanceParams params);
}
