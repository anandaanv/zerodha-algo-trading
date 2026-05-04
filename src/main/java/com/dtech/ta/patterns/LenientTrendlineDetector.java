package com.dtech.ta.patterns;

import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.ta.BarTuple;
import com.dtech.ta.OHLC;
import com.dtech.ta.TrendLineCalculated;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lenient trendline detector — no virginity, no EMA-zone gating.
 *
 * For each new pivot P2 (current pivot being scanned), connect it to the
 * prior N same-type pivots (default 5). Each (P_old, P2) pair is a trendline
 * candidate. After P2, watch for a breakout candle close that crosses the line.
 *
 * Generates many more candidates than the strict TLB — the idea is that ML
 * will sift quality from the noise.
 */
public class LenientTrendlineDetector {

    private static final int DEFAULT_PRIOR_PIVOTS = 5;
    private static final int DEFAULT_MIN_GAP_BARS = 20;
    private static final double DEFAULT_TARGET_FRACTION = 1.0;
    private static final int DEFAULT_MIN_TOUCHES = 1;       // require ≥N mid-bar touches beyond P1/P2
    private static final double DEFAULT_TOUCH_TOL_ATR = 0.5; // bar's high/low must come within 0.5×ATR of trendline to count as a touch
    private static final int DEFAULT_EMA_FAST = 100;
    private static final int DEFAULT_EMA_SLOW = 200;
    private static final int CONFIRM_LOOKAHEAD = 5;

    private final BarSeries series;
    private final double[] atrValues;
    private final int priorPivots;
    private final int minGapBars;
    private final double targetFraction;
    private final int minTouches;
    private final double touchTolAtr;
    private final double[] ema100;
    private final double[] ema200;

    public LenientTrendlineDetector(BarSeries series, double[] atrValues) {
        this(series, atrValues, DEFAULT_PRIOR_PIVOTS, DEFAULT_MIN_GAP_BARS, DEFAULT_TARGET_FRACTION,
             DEFAULT_EMA_FAST, DEFAULT_EMA_SLOW);
    }

    public LenientTrendlineDetector(BarSeries series, double[] atrValues, int priorPivots,
                                      int minGapBars, double targetFraction,
                                      int emaFast, int emaSlow) {
        this(series, atrValues, priorPivots, minGapBars, targetFraction, emaFast, emaSlow,
             DEFAULT_MIN_TOUCHES, DEFAULT_TOUCH_TOL_ATR);
    }

    public LenientTrendlineDetector(BarSeries series, double[] atrValues, int priorPivots,
                                      int minGapBars, double targetFraction,
                                      int emaFast, int emaSlow,
                                      int minTouches, double touchTolAtr) {
        this.series = series;
        this.atrValues = atrValues;
        this.priorPivots = priorPivots;
        this.minGapBars = minGapBars;
        this.targetFraction = targetFraction;
        this.minTouches = minTouches;
        this.touchTolAtr = touchTolAtr;

        ClosePriceIndicator close = new ClosePriceIndicator(series);
        EMAIndicator e1 = new EMAIndicator(close, emaFast);
        EMAIndicator e2 = new EMAIndicator(close, emaSlow);
        int n = series.getBarCount();
        this.ema100 = new double[n];
        this.ema200 = new double[n];
        for (int i = 0; i < n; i++) {
            this.ema100[i] = e1.getValue(i).doubleValue();
            this.ema200[i] = e2.getValue(i).doubleValue();
        }
    }

    public List<TrendlineBreakoutPattern> detect(List<ZigZagPoint> pivots,
                                                  List<Bar> bars,
                                                  Map<Instant, Integer> tsToIdx) {
        List<TrendlineBreakoutPattern> results = new ArrayList<>();

        // For each pivot (treated as P2), connect to prior N same-type pivots
        for (int p2Pos = priorPivots; p2Pos < pivots.size(); p2Pos++) {
            ZigZagPoint p2 = pivots.get(p2Pos);
            Integer p2Idx = tsToIdx.get(p2.getTimestamp());
            if (p2Idx == null || p2Idx >= atrValues.length) continue;

            // Walk backward, collect up to priorPivots same-type predecessors
            int priorFound = 0;
            for (int p1Pos = p2Pos - 1; p1Pos >= 0 && priorFound < priorPivots; p1Pos--) {
                ZigZagPoint p1 = pivots.get(p1Pos);
                if (p1.getType() != p2.getType()) continue;
                priorFound++;

                Integer p1Idx = tsToIdx.get(p1.getTimestamp());
                if (p1Idx == null) continue;
                if (p2Idx - p1Idx < minGapBars) continue;

                // Build trendline (P1, P2)
                double p1Price = p1.getValue();
                double p2Price = p2.getValue();
                double slope = (p2Price - p1Price) / (double)(p2Idx - p1Idx);

                // Direction of breakout = type of pivot
                // For HIGH pivots (resistance trendline), bullish breakout = close above line
                // For LOW pivots (support trendline), bearish breakout = close below line
                boolean bullish = p2.getType() == ZigZagPoint.Type.HIGH;

                // Find priorSwing for AB target distance — the most recent OPPOSITE-type pivot before P1
                ZigZagPoint priorSwing = null;
                for (int j = p1Pos - 1; j >= 0; j--) {
                    if (pivots.get(j).getType() != p1.getType()) {
                        priorSwing = pivots.get(j);
                        break;
                    }
                }
                if (priorSwing == null) continue;

                // Build trendline visualization object
                OHLC ohlcType = bullish ? OHLC.H : OHLC.L;
                List<BarTuple> trendPoints = new ArrayList<>();
                trendPoints.add(new BarTuple(p1Idx, bars.get(p1Idx), ohlcType));
                trendPoints.add(new BarTuple(p2Idx, bars.get(p2Idx), ohlcType));
                double intercept = p1Price - slope * p1Idx;
                TrendLineCalculated trendline = new TrendLineCalculated(series, slope, intercept, trendPoints, !bullish);

                // Mid-touch count: real trendlines get tested at intermediate bars.
                if (minTouches > 0) {
                    int touches = countMidTouches(bars, p1Idx, p2Idx, p1Price, slope, bullish);
                    if (touches < minTouches) continue;
                }

                // Look for breakout after P2
                TrendlineBreakoutPattern pattern = findBreakout(p1, p2, priorSwing, trendline,
                                                                  bullish, bars, p2Idx, slope, p1Idx);
                if (pattern != null) {
                    results.add(pattern);
                }
            }
        }
        return results;
    }

    private TrendlineBreakoutPattern findBreakout(ZigZagPoint p1, ZigZagPoint p2,
                                                    ZigZagPoint priorSwing,
                                                    TrendLineCalculated trendline,
                                                    boolean bullish, List<Bar> bars,
                                                    int p2Idx, double slope, int p1Idx) {
        double abDistance = Math.abs(priorSwing.getValue() - p1.getValue());
        double p1Price = p1.getValue();

        for (int i = p2Idx + 1; i < bars.size() - 1; i++) {
            Bar breakoutCandle = bars.get(i);
            double close = breakoutCandle.getClosePrice().doubleValue();
            double trendlinePrice = p1Price + slope * (i - p1Idx);
            boolean broken = bullish ? close > trendlinePrice : close < trendlinePrice;

            if (broken) {
                double breakoutHigh = breakoutCandle.getHighPrice().doubleValue();
                double breakoutLow = breakoutCandle.getLowPrice().doubleValue();

                for (int j = i + 1; j < Math.min(bars.size(), i + CONFIRM_LOOKAHEAD); j++) {
                    Bar confirmCandle = bars.get(j);
                    boolean confirmed;
                    double entryPrice;
                    if (bullish) {
                        confirmed = confirmCandle.getHighPrice().doubleValue() > breakoutHigh;
                        entryPrice = breakoutHigh;
                    } else {
                        confirmed = confirmCandle.getLowPrice().doubleValue() < breakoutLow;
                        entryPrice = breakoutLow;
                    }

                    if (confirmed) {
                        double slTrendline = p1Price + slope * (j - p1Idx);
                        double stopLossBreakoutCandle = (breakoutHigh + breakoutLow) / 2.0;
                        double scaledAB = abDistance * targetFraction;
                        double target = bullish ? entryPrice + scaledAB : entryPrice - scaledAB;
                        return new TrendlineBreakoutPattern(
                                p1, p2, priorSwing, trendline, bullish,
                                abDistance, entryPrice,
                                slTrendline, stopLossBreakoutCandle,
                                target, j);
                    }
                }
                return null;
            }
        }
        return null;
    }

    /**
     * Count bars between P1 and P2 (exclusive) where price wicks within
     * touchTolAtr × ATR of the trendline.
     * For bullish (resistance line connecting highs): bar.high must be near the line from below.
     * For bearish (support line connecting lows): bar.low must be near the line from above.
     */
    private int countMidTouches(List<Bar> bars, int p1Idx, int p2Idx,
                                  double p1Price, double slope, boolean bullish) {
        int touches = 0;
        // Don't count consecutive bars as separate touches — debounce
        int lastTouchIdx = -10;
        int debounce = 5;  // require 5-bar gap between distinct touches
        for (int i = p1Idx + 1; i < p2Idx; i++) {
            if (i >= atrValues.length) break;
            double tline = p1Price + slope * (i - p1Idx);
            double tol = touchTolAtr * atrValues[i];
            Bar b = bars.get(i);
            if (bullish) {
                double h = b.getHighPrice().doubleValue();
                // Bar's high should approach the line from below: h is within tol of tline AND h <= tline
                if (h <= tline && Math.abs(h - tline) <= tol && (i - lastTouchIdx) >= debounce) {
                    touches++;
                    lastTouchIdx = i;
                }
            } else {
                double l = b.getLowPrice().doubleValue();
                if (l >= tline && Math.abs(l - tline) <= tol && (i - lastTouchIdx) >= debounce) {
                    touches++;
                    lastTouchIdx = i;
                }
            }
        }
        return touches;
    }
}
