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
 * Detects Trendline Breakout patterns anchored to EMA + ATR zones.
 *
 * Runs detection on BOTH 100 EMA and 200 EMA — pivots from either qualify.
 *
 * Validation rules:
 *   1. P1 and P2 must be at least MIN_GAP_BARS candles apart
 *   2. Trendline between P1-P2 must be "virgin" — no candle close has crossed it
 *   3. Prior swing must be >= 1.5 ATR extended from EMA (real move, not noise)
 *   4. Breakout confirmed by next candle breaking breakout candle's high/low
 */
public class TrendlineBreakoutDetector {

    private static final int EMA_PERIOD_FAST = 100;
    private static final int EMA_PERIOD_SLOW = 200;
    private static final double EMA_ZONE_ATR = 1.0;
    private static final double EXTENSION_ATR = 1.5;
    private static final int MIN_GAP_BARS = 100;  // minimum candles between P1 and P2

    private final BarSeries series;
    private final double[] ema100;
    private final double[] ema200;
    private final double[] atrValues;

    public TrendlineBreakoutDetector(BarSeries series, double[] atrValues) {
        this.series = series;
        this.atrValues = atrValues;

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        EMAIndicator emaFast = new EMAIndicator(closePrice, EMA_PERIOD_FAST);
        EMAIndicator emaSlow = new EMAIndicator(closePrice, EMA_PERIOD_SLOW);

        int n = series.getBarCount();
        this.ema100 = new double[n];
        this.ema200 = new double[n];
        for (int i = 0; i < n; i++) {
            this.ema100[i] = emaFast.getValue(i).doubleValue();
            this.ema200[i] = emaSlow.getValue(i).doubleValue();
        }
    }

    public List<TrendlineBreakoutPattern> detect(List<ZigZagPoint> pivots,
                                                   List<Bar> bars,
                                                   Map<Instant, Integer> tsToIdx) {
        List<TrendlineBreakoutPattern> results = new ArrayList<>();

        for (int i = 0; i < pivots.size(); i++) {
            ZigZagPoint candidateP1 = pivots.get(i);
            Integer p1Idx = tsToIdx.get(candidateP1.getTimestamp());
            if (p1Idx == null || p1Idx >= atrValues.length) continue;

            // P1 must be in EMA zone of EITHER 100 or 200 EMA
            boolean p1InEma100 = isInEmaZone(candidateP1.getValue(), p1Idx, ema100);
            boolean p1InEma200 = isInEmaZone(candidateP1.getValue(), p1Idx, ema200);
            if (!p1InEma100 && !p1InEma200) continue;

            // Find priorSwing (opposite type) — must be extended from the same EMA
            ZigZagPoint priorSwing = null;
            for (int j = i - 1; j >= 0; j--) {
                if (pivots.get(j).getType() != candidateP1.getType()) {
                    priorSwing = pivots.get(j);
                    break;
                }
            }
            if (priorSwing == null) continue;

            Integer priorIdx = tsToIdx.get(priorSwing.getTimestamp());
            if (priorIdx == null || priorIdx >= atrValues.length) continue;

            // priorSwing must be extended from at least one of the EMAs that P1 touches
            boolean priorExtended100 = p1InEma100 && isExtendedFromEma(priorSwing.getValue(), priorIdx, ema100);
            boolean priorExtended200 = p1InEma200 && isExtendedFromEma(priorSwing.getValue(), priorIdx, ema200);
            if (!priorExtended100 && !priorExtended200) continue;

            // Direction
            double[] activeEma = priorExtended200 ? ema200 : ema100;
            boolean priorAboveEma = priorSwing.getValue() > activeEma[priorIdx];
            boolean bullish = !priorAboveEma;

            if (bullish && !candidateP1.isHigh()) continue;
            if (!bullish && !candidateP1.isLow()) continue;

            // Find P2: same type, in EMA zone, >= MIN_GAP_BARS apart, with extended prior swing
            for (int k = i + 1; k < pivots.size(); k++) {
                ZigZagPoint candidateP2 = pivots.get(k);
                if (candidateP2.getType() != candidateP1.getType()) continue;

                Integer p2Idx = tsToIdx.get(candidateP2.getTimestamp());
                if (p2Idx == null || p2Idx >= atrValues.length) continue;

                // Minimum gap check
                if (p2Idx - p1Idx < MIN_GAP_BARS) continue;

                // P2 must be in EMA zone of either EMA
                boolean p2InEma100 = isInEmaZone(candidateP2.getValue(), p2Idx, ema100);
                boolean p2InEma200 = isInEmaZone(candidateP2.getValue(), p2Idx, ema200);
                if (!p2InEma100 && !p2InEma200) continue;

                // P2's prior opposite-type pivot must be extended
                ZigZagPoint p2PriorSwing = null;
                for (int m = k - 1; m >= 0; m--) {
                    if (pivots.get(m).getType() != candidateP2.getType()) {
                        p2PriorSwing = pivots.get(m);
                        break;
                    }
                }
                if (p2PriorSwing == null) continue;
                Integer p2PriorIdx = tsToIdx.get(p2PriorSwing.getTimestamp());
                if (p2PriorIdx == null || p2PriorIdx >= atrValues.length) continue;

                boolean p2PriorExt100 = p2InEma100 && isExtendedFromEma(p2PriorSwing.getValue(), p2PriorIdx, ema100);
                boolean p2PriorExt200 = p2InEma200 && isExtendedFromEma(p2PriorSwing.getValue(), p2PriorIdx, ema200);
                if (!p2PriorExt100 && !p2PriorExt200) continue;

                // Build trendline
                double p1Price = candidateP1.getValue();
                double p2Price = candidateP2.getValue();
                double slope = (p2Price - p1Price) / (double)(p2Idx - p1Idx);

                // VIRGIN TRENDLINE CHECK — no candle close has crossed the trendline between P1 and P2
                if (!isVirginTrendline(bars, p1Idx, p2Idx, p1Price, slope, bullish)) continue;

                OHLC ohlcType = bullish ? OHLC.H : OHLC.L;
                List<BarTuple> trendPoints = new ArrayList<>();
                trendPoints.add(new BarTuple(p1Idx, bars.get(p1Idx), ohlcType));
                trendPoints.add(new BarTuple(p2Idx, bars.get(p2Idx), ohlcType));

                double intercept = p1Price - slope * p1Idx;
                TrendLineCalculated trendline = new TrendLineCalculated(series, slope, intercept, trendPoints, !bullish);

                TrendlineBreakoutPattern pattern = findBreakout(
                        candidateP1, candidateP2, priorSwing, trendline,
                        bullish, bars, p2Idx, slope, p1Idx);

                if (pattern != null) {
                    results.add(pattern);
                }
                break; // first valid P2 only
            }
        }

        return results;
    }

    /**
     * Check that the trendline is "virgin" — no candle close has crossed it
     * between P1 and P2.
     *
     * For bullish (trendline connects highs): no close ABOVE the trendline
     * For bearish (trendline connects lows): no close BELOW the trendline
     */
    private boolean isVirginTrendline(List<Bar> bars, int p1Idx, int p2Idx,
                                       double p1Price, double slope, boolean bullish) {
        for (int i = p1Idx + 1; i < p2Idx; i++) {
            double close = bars.get(i).getClosePrice().doubleValue();
            double trendlineAt = p1Price + slope * (i - p1Idx);

            if (bullish && close > trendlineAt) return false;   // bullish trendline = resistance, no close above
            if (!bullish && close < trendlineAt) return false;  // bearish trendline = support, no close below
        }
        return true;
    }

    private boolean isInEmaZone(double price, int barIndex, double[] ema) {
        if (barIndex >= ema.length || barIndex >= atrValues.length) return false;
        return Math.abs(price - ema[barIndex]) <= EMA_ZONE_ATR * atrValues[barIndex];
    }

    private boolean isExtendedFromEma(double price, int barIndex, double[] ema) {
        if (barIndex >= ema.length || barIndex >= atrValues.length) return false;
        return Math.abs(price - ema[barIndex]) >= EXTENSION_ATR * atrValues[barIndex];
    }

    /**
     * Look for trendline break after P2 with next-candle confirmation.
     */
    private TrendlineBreakoutPattern findBreakout(ZigZagPoint p1, ZigZagPoint p2,
                                                    ZigZagPoint priorSwing,
                                                    TrendLineCalculated trendline,
                                                    boolean bullish, List<Bar> bars,
                                                    int p2Idx, double slopeAbsolute, int p1Idx) {
        double abDistance = Math.abs(priorSwing.getValue() - p1.getValue());
        double p1Price = p1.getValue();

        for (int i = p2Idx + 1; i < bars.size() - 1; i++) {
            Bar breakoutCandle = bars.get(i);
            double close = breakoutCandle.getClosePrice().doubleValue();
            double trendlinePrice = p1Price + slopeAbsolute * (i - p1Idx);

            boolean broken = bullish ? close > trendlinePrice : close < trendlinePrice;

            if (broken) {
                double breakoutHigh = breakoutCandle.getHighPrice().doubleValue();
                double breakoutLow = breakoutCandle.getLowPrice().doubleValue();

                for (int j = i + 1; j < Math.min(bars.size(), i + 5); j++) {
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
                        double slTrendline = p1Price + slopeAbsolute * (j - p1Idx);
                        double stopLossBreakoutCandle = (breakoutHigh + breakoutLow) / 2.0;
                        double target = bullish ? entryPrice + abDistance : entryPrice - abDistance;

                        return new TrendlineBreakoutPattern(
                                p1, p2, priorSwing, trendline, bullish,
                                abDistance, entryPrice,
                                slTrendline, stopLossBreakoutCandle,
                                target, j);
                    }
                }
                return null; // breakout but no confirmation
            }
        }
        return null;
    }
}
