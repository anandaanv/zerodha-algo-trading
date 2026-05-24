package com.dtech.aitrader.v2.narrative.pivot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Default implementation of SeriesSignificantPivotEngine.
 *
 * Ported from ZigZagService.detect() with adaptations for 1D numeric series:
 * - True Range computed as abs(series[i] - series[i-1]) instead of high-low range.
 * - Single value per bar instead of (high, low) pair.
 * - No BACKTEST mode finalization or pivot metrics (retracement/extension).
 * - ATR and relative volatility computed identically to ZigZagService.
 *
 * Algorithm:
 * 1. Compute True Range and Wilder ATR.
 * 2. Compute relative volatility (EMA of TR/value ratio).
 * 3. Track pivot state (UP/DOWN/NONE direction).
 * 4. Detect reversals when move exceeds threshold × hysteresis.
 * 5. Compute significance for each pivot.
 */
public class DefaultSeriesPivotEngine implements SeriesSignificantPivotEngine {

    @Override
    public List<SeriesPivot> detect(double[] series, SignificanceParams params) {
        if (series == null || series.length == 0) {
            return Collections.emptyList();
        }

        int n = series.length;
        double[] tr = new double[n];
        double[] atr = new double[n];

        // Compute True Range and Wilder ATR
        computeTrueRangeAndAtr(series, tr, atr, params.atrLength());

        // Compute relative volatility (EMA of TR/series value ratio)
        double[] rvol = computeRvolPct(series, tr, params.rvolWindow());

        // Detect pivots
        return detectPivots(series, tr, atr, rvol, params);
    }

    /**
     * Compute True Range (1D adaptation) and Wilder ATR.
     * TR for 1D series: abs(series[i] - series[i-1])
     * ATR: Wilder's smoothing formula, identical to ZigZagService.
     */
    private void computeTrueRangeAndAtr(double[] series, double[] tr, double[] atr, int atrLength) {
        int n = series.length;

        // Compute True Range
        for (int i = 0; i < n; i++) {
            tr[i] = (i == 0) ? 0.0 : Math.abs(series[i] - series[i - 1]);
        }

        // Compute Wilder ATR
        for (int i = 0; i < n; i++) {
            if (i == 0) {
                atr[i] = tr[i];
            } else if (i < atrLength) {
                atr[i] = ((atr[i - 1] * i) + tr[i]) / (i + 1);
            } else if (i == atrLength) {
                double sum = 0.0;
                for (int k = i - atrLength + 1; k <= i; k++) {
                    sum += tr[k];
                }
                atr[i] = sum / atrLength;
            } else {
                atr[i] = ((atr[i - 1] * (atrLength - 1)) + tr[i]) / atrLength;
            }
        }
    }

    /**
     * Compute relative volatility as EMA of (True Range / abs(series value)) ratio.
     * Same formula as ZigZagService.computeRvolPct().
     */
    private double[] computeRvolPct(double[] series, double[] tr, int window) {
        int n = series.length;
        double[] rvol = new double[n];
        if (n == 0) {
            return rvol;
        }

        double alpha = 2.0 / (window + 1.0);
        for (int i = 0; i < n; i++) {
            double absVal = Math.abs(series[i]);
            double rv = absVal > 0 ? tr[i] / absVal : 0.0;
            if (i == 0) {
                rvol[i] = rv;
            } else {
                rvol[i] = alpha * rv + (1 - alpha) * rvol[i - 1];
            }
        }
        return rvol;
    }

    /**
     * Main pivot detection loop.
     * Ported from ZigZagService.detect() lines 99-187, adapted for 1D series.
     * Differences:
     * - Single value per bar (currExtValue) instead of (high, low) pair.
     * - Use abs(extValue) in percentage threshold calculation to handle zero-crossing.
     * - Drop BACKTEST finalization (lines 179-187).
     * - Compute significance at each pivot confirmation.
     */
    private List<SeriesPivot> detectPivots(double[] series, double[] tr, double[] atr,
                                            double[] rvol, SignificanceParams params) {
        int n = series.length;
        List<SeriesPivot> pivots = new ArrayList<>();

        enum Dir { UP, DOWN, NONE }
        Dir dir = Dir.NONE;

        int lastExtIdx = 0;
        double lastExtValue = series[0];
        double currExtValue = series[0];
        int currExtIdx = 0;

        for (int i = 1; i < n; i++) {
            double value = series[i];

            if (dir == Dir.NONE) {
                // Determine initial direction when move exceeds threshold
                double move = value - lastExtValue;
                double effectivePct = params.dynamicPctEnabled() ? params.volMult() * rvol[i] : params.pctMin();
                double thr = Math.max(effectivePct * Math.abs(lastExtValue), params.atrMult() * atr[i]);
                // Ensure threshold is never zero to avoid false positives on flat/zero series
                thr = Math.max(thr, 1e-10);

                if (Math.abs(move) >= thr) {
                    dir = move > 0 ? Dir.UP : Dir.DOWN;
                    currExtValue = value;
                    currExtIdx = i;
                }
                continue;
            }

            // Extend current leg extremes
            if (dir == Dir.UP) {
                if (value > currExtValue) {
                    currExtValue = value;
                    currExtIdx = i;
                }
                // Check for reversal (DOWN move)
                double reversalMove = currExtValue - value;
                double effectivePct = params.dynamicPctEnabled() ? params.volMult() * rvol[i] : params.pctMin();
                double baseThr = Math.max(effectivePct * Math.abs(currExtValue), params.atrMult() * atr[i]);
                baseThr = Math.max(baseThr, 1e-10); // Ensure minimum threshold
                double revThr = baseThr * params.hysteresis();
                boolean spaced = (i - lastExtIdx) >= params.minBarsBetweenPivots();

                if (reversalMove >= revThr && spaced) {
                    // Confirm PEAK pivot at currExtIdx
                    double significance = computeSignificance(currExtValue, lastExtValue, params.atrMult(), atr[currExtIdx]);
                    pivots.add(new SeriesPivot(currExtIdx, currExtValue, PivotKind.PEAK, significance, atr[currExtIdx]));
                    lastExtIdx = currExtIdx;
                    lastExtValue = currExtValue;
                    dir = Dir.DOWN;
                    currExtValue = value;
                    currExtIdx = i;
                }
            } else { // DOWN
                if (value < currExtValue) {
                    currExtValue = value;
                    currExtIdx = i;
                }
                // Check for reversal (UP move)
                double reversalMove = value - currExtValue;
                double effectivePct = params.dynamicPctEnabled() ? params.volMult() * rvol[i] : params.pctMin();
                double baseThr = Math.max(effectivePct * Math.abs(currExtValue), params.atrMult() * atr[i]);
                baseThr = Math.max(baseThr, 1e-10); // Ensure minimum threshold
                double revThr = baseThr * params.hysteresis();
                boolean spaced = (i - lastExtIdx) >= params.minBarsBetweenPivots();

                if (reversalMove >= revThr && spaced) {
                    // Confirm TROUGH pivot at currExtIdx
                    double significance = computeSignificance(currExtValue, lastExtValue, params.atrMult(), atr[currExtIdx]);
                    pivots.add(new SeriesPivot(currExtIdx, currExtValue, PivotKind.TROUGH, significance, atr[currExtIdx]));
                    lastExtIdx = currExtIdx;
                    lastExtValue = currExtValue;
                    dir = Dir.UP;
                    currExtValue = value;
                    currExtIdx = i;
                }
            }
        }

        return pivots;
    }

    /**
     * Compute normalized significance score for a pivot.
     *
     * Formula: clamp01(prominence / (atrMult * atr[pivotIdx]))
     * where prominence = abs(pivotValue - lastExtValue)
     *
     * If no prior pivot (lastExtValue uninitialized), use abs(pivotValue - series[0]).
     */
    private double computeSignificance(double pivotValue, double lastExtValue, double atrMult, double atrAtPivot) {
        double prominence = Math.abs(pivotValue - lastExtValue);
        double denominator = atrMult * atrAtPivot;
        if (denominator < 1e-10) {
            return 0.0;
        }
        double raw = prominence / denominator;
        return Math.max(0.0, Math.min(1.0, raw));
    }
}
