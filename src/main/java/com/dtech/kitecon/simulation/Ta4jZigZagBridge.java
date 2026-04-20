package com.dtech.kitecon.simulation;

import com.dtech.chartpattern.zigzag.ZigZagPoint;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.zigzag.ZigZagPivotHighIndicator;
import org.ta4j.core.indicators.zigzag.ZigZagPivotLowIndicator;
import org.ta4j.core.indicators.zigzag.ZigZagState;
import org.ta4j.core.indicators.zigzag.ZigZagStateIndicator;
import org.ta4j.core.indicators.zigzag.ZigZagTrend;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Bridge between ta4j's ZigZagStateIndicator and our ZigZagPoint format.
 * Uses ta4j's built-in CachedIndicator + OnChange system for O(1) per-bar updates.
 *
 * Usage:
 *   Ta4jZigZagBridge bridge = new Ta4jZigZagBridge(series, atrMultiplier);
 *   // series.addBar(newBar) → ZigZag auto-updates via OnChange
 *   List<ZigZagPoint> pivots = bridge.getPivotsWithTrailingExtreme();
 */
public class Ta4jZigZagBridge {

    private final BarSeries series;
    private final ZigZagStateIndicator stateIndicator;
    private final ZigZagPivotHighIndicator pivotHighIndicator;
    private final ZigZagPivotLowIndicator pivotLowIndicator;
    private final ATRIndicator atrIndicator;
    private final List<ZigZagPoint> confirmedPivots = new ArrayList<>();
    private int lastCheckedIndex = -1;

    /**
     * Create a bridge wrapping ta4j's ZigZag on the given series.
     *
     * @param series        the bar series (bars can be added later — ZigZag auto-updates)
     * @param atrPeriod     ATR period for reversal threshold
     * @param atrMultiplier multiplier on ATR for reversal amount
     */
    public Ta4jZigZagBridge(BarSeries series, int atrPeriod, double atrMultiplier) {
        this.series = series;
        this.atrIndicator = new ATRIndicator(series, atrPeriod);

        // Use the simple constructor with default close price and ATR-based threshold
        // ZigZagStateIndicator(BarSeries) uses ATR(14) * 1.0 by default
        // For custom params, use ZigZagStateIndicator(price, reversalAmount)
        var closePrice = new org.ta4j.core.indicators.helpers.ClosePriceIndicator(series);
        this.stateIndicator = new ZigZagStateIndicator(closePrice, atrIndicator);
        this.pivotHighIndicator = new ZigZagPivotHighIndicator(stateIndicator);
        this.pivotLowIndicator = new ZigZagPivotLowIndicator(stateIndicator);
    }

    /**
     * Scan for any new pivots confirmed since last check.
     * Call this after adding bars to the series.
     *
     * @return true if new pivots were confirmed
     */
    public boolean updatePivots() {
        int endIndex = series.getEndIndex();
        boolean newPivot = false;

        for (int i = Math.max(lastCheckedIndex + 1, series.getBeginIndex()); i <= endIndex; i++) {
            if (pivotHighIndicator.getValue(i)) {
                ZigZagState state = stateIndicator.getValue(i);
                Bar pivotBar = series.getBar(state.getLastHighIndex());
                confirmedPivots.add(ZigZagPoint.builder()
                        .type(ZigZagPoint.Type.HIGH)
                        .value(state.getLastHighPrice().doubleValue())
                        .timestamp(pivotBar.getEndTime())
                        .sequence(pivotBar.getEndTime().getEpochSecond())
                        .barIndex(state.getLastHighIndex())
                        .atrAtPivot(atrIndicator.getValue(state.getLastHighIndex()).doubleValue())
                        .build());
                newPivot = true;
            }
            if (pivotLowIndicator.getValue(i)) {
                ZigZagState state = stateIndicator.getValue(i);
                Bar pivotBar = series.getBar(state.getLastLowIndex());
                confirmedPivots.add(ZigZagPoint.builder()
                        .type(ZigZagPoint.Type.LOW)
                        .value(state.getLastLowPrice().doubleValue())
                        .timestamp(pivotBar.getEndTime())
                        .sequence(pivotBar.getEndTime().getEpochSecond())
                        .barIndex(state.getLastLowIndex())
                        .atrAtPivot(atrIndicator.getValue(state.getLastLowIndex()).doubleValue())
                        .build());
                newPivot = true;
            }
        }

        lastCheckedIndex = endIndex;
        return newPivot;
    }

    /**
     * Get confirmed pivots plus a trailing extreme (matching BACKTEST mode behavior).
     */
    public List<ZigZagPoint> getPivotsWithTrailingExtreme() {
        updatePivots();

        List<ZigZagPoint> result = new ArrayList<>(confirmedPivots);

        if (!result.isEmpty() && series.getEndIndex() >= 0) {
            ZigZagState currentState = stateIndicator.getValue(series.getEndIndex());
            ZigZagPoint lastPivot = result.get(result.size() - 1);
            Bar currentBar = series.getBar(series.getEndIndex());
            Instant ts = currentBar.getEndTime();

            // Append trailing extreme (opposite of last confirmed pivot)
            if (lastPivot.getType() == ZigZagPoint.Type.HIGH) {
                result.add(ZigZagPoint.builder()
                        .type(ZigZagPoint.Type.LOW)
                        .value(currentState.getLastExtremePrice().doubleValue())
                        .timestamp(ts)
                        .sequence(ts.getEpochSecond())
                        .barIndex(currentState.getLastExtremeIndex())
                        .atrAtPivot(atrIndicator.getValue(series.getEndIndex()).doubleValue())
                        .build());
            } else {
                result.add(ZigZagPoint.builder()
                        .type(ZigZagPoint.Type.HIGH)
                        .value(currentState.getLastExtremePrice().doubleValue())
                        .timestamp(ts)
                        .sequence(ts.getEpochSecond())
                        .barIndex(currentState.getLastExtremeIndex())
                        .atrAtPivot(atrIndicator.getValue(series.getEndIndex()).doubleValue())
                        .build());
            }
        }

        return result;
    }

    /**
     * Get only confirmed pivots (no trailing extreme).
     */
    public List<ZigZagPoint> getConfirmedPivots() {
        updatePivots();
        return new ArrayList<>(confirmedPivots);
    }

    public int getPivotCount() {
        return confirmedPivots.size();
    }

    public ZigZagStateIndicator getStateIndicator() {
        return stateIndicator;
    }

    public ATRIndicator getAtrIndicator() {
        return atrIndicator;
    }
}
