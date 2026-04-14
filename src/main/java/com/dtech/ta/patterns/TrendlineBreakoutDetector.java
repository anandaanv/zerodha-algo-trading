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
 * Detects Trendline Breakout patterns anchored to the 100 EMA + ATR zone.
 *
 * Logic:
 * - Find ZigZag pivots that land within 1 ATR of the 100 EMA (the "EMA zone")
 * - Two consecutive same-type pivots in the zone form P1 and P2
 * - The swing before P1 determines direction (above EMA = bearish, below = bullish)
 * - Draw trendline through P1-P2, detect break, project AB=CD target
 */
public class TrendlineBreakoutDetector {

    private static final int EMA_PERIOD = 200;
    private static final double EMA_ZONE_ATR = 1.0;      // pivot must be within 1 ATR of EMA
    private static final double EXTENSION_ATR = 1.5;      // priorSwing must be >= 1.5 ATR away from EMA

    private final BarSeries series;
    private final double[] emaValues;
    private final double[] atrValues;

    public TrendlineBreakoutDetector(BarSeries series, double[] atrValues) {
        this.series = series;
        this.atrValues = atrValues;

        // Compute EMA(100)
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        EMAIndicator ema = new EMAIndicator(closePrice, EMA_PERIOD);
        this.emaValues = new double[series.getBarCount()];
        for (int i = 0; i < series.getBarCount(); i++) {
            this.emaValues[i] = ema.getValue(i).doubleValue();
        }
    }

    /**
     * Detect trendline breakout patterns from ZigZag pivots.
     *
     * Pivot selection: a valid EMA-crossover pivot is a ZigZag pivot where:
     *   1. The pivot is within 1 ATR of the 100 EMA (price crossed through EMA)
     *   2. The prior opposite-type pivot was >= 1.5 ATR AWAY from EMA on the other side
     *      (confirms price made a meaningful move before crossing EMA)
     *
     * This filters out noise — only pivots where price genuinely crossed through EMA
     * after an extended move on one side qualify as trendline anchors.
     */
    public List<TrendlineBreakoutPattern> detect(List<ZigZagPoint> pivots,
                                                   List<Bar> bars,
                                                   Map<Instant, Integer> tsToIdx) {
        List<TrendlineBreakoutPattern> results = new ArrayList<>();

        for (int i = 0; i < pivots.size(); i++) {
            ZigZagPoint candidateP1 = pivots.get(i);
            Integer p1Idx = tsToIdx.get(candidateP1.getTimestamp());
            if (p1Idx == null || p1Idx >= emaValues.length || p1Idx >= atrValues.length) continue;

            // Step 1: P1 must be within EMA zone (crossing point)
            if (!isInEmaZone(candidateP1.getValue(), p1Idx)) continue;

            // Step 2: Find priorSwing (opposite type) — must be extended away from EMA
            ZigZagPoint priorSwing = null;
            for (int j = i - 1; j >= 0; j--) {
                if (pivots.get(j).getType() != candidateP1.getType()) {
                    priorSwing = pivots.get(j);
                    break;
                }
            }
            if (priorSwing == null) continue;

            Integer priorIdx = tsToIdx.get(priorSwing.getTimestamp());
            if (priorIdx == null || priorIdx >= emaValues.length || priorIdx >= atrValues.length) continue;

            // Step 3: priorSwing must be >= 1.5 ATR away from EMA (extended move)
            if (!isExtendedFromEma(priorSwing.getValue(), priorIdx)) continue;

            // Direction: priorSwing above EMA = bearish, below = bullish
            boolean priorAboveEma = priorSwing.getValue() > emaValues[priorIdx];
            boolean bullish = !priorAboveEma;

            // For bearish: P1 is LOW (price dropped from above to EMA)
            // For bullish: P1 is HIGH (price rose from below to EMA)
            if (bullish && !candidateP1.isHigh()) continue;
            if (!bullish && !candidateP1.isLow()) continue;

            // Find P2: next same-type pivot also in EMA zone, with its own priorSwing extended
            for (int k = i + 1; k < pivots.size(); k++) {
                ZigZagPoint candidateP2 = pivots.get(k);
                if (candidateP2.getType() != candidateP1.getType()) continue;

                Integer p2Idx = tsToIdx.get(candidateP2.getTimestamp());
                if (p2Idx == null || p2Idx >= emaValues.length || p2Idx >= atrValues.length) continue;

                // P2 must be in EMA zone
                if (!isInEmaZone(candidateP2.getValue(), p2Idx)) continue;

                // P2's prior opposite-type pivot must also be extended from EMA
                // (the bounce between P1 and P2 must have gone >= 1.5 ATR away)
                ZigZagPoint p2PriorSwing = null;
                for (int m = k - 1; m >= 0; m--) {
                    if (pivots.get(m).getType() != candidateP2.getType()) {
                        p2PriorSwing = pivots.get(m);
                        break;
                    }
                }
                if (p2PriorSwing == null) continue;
                Integer p2PriorIdx = tsToIdx.get(p2PriorSwing.getTimestamp());
                if (p2PriorIdx == null || p2PriorIdx >= emaValues.length || p2PriorIdx >= atrValues.length) continue;
                if (!isExtendedFromEma(p2PriorSwing.getValue(), p2PriorIdx)) continue;

                // Valid P1-P2 pair. Build trendline.
                double p1Price = candidateP1.getValue();
                double p2Price = candidateP2.getValue();
                double slope = (p2Price - p1Price) / (double)(p2Idx - p1Idx);
                double intercept = p1Price - slope * p1Idx;

                OHLC ohlcType = bullish ? OHLC.H : OHLC.L;
                List<BarTuple> trendPoints = new ArrayList<>();
                trendPoints.add(new BarTuple(p1Idx, bars.get(p1Idx), ohlcType));
                trendPoints.add(new BarTuple(p2Idx, bars.get(p2Idx), ohlcType));

                boolean isSupport = !bullish;
                TrendLineCalculated trendline = new TrendLineCalculated(series, slope, intercept, trendPoints, isSupport);

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

    /** Pivot is within 1 ATR of the 100 EMA — the EMA crossing zone. */
    private boolean isInEmaZone(double price, int barIndex) {
        if (barIndex >= emaValues.length || barIndex >= atrValues.length) return false;
        double ema = emaValues[barIndex];
        double atr = atrValues[barIndex];
        return Math.abs(price - ema) <= EMA_ZONE_ATR * atr;
    }

    /** Price is >= 1.5 ATR away from EMA — confirms an extended move on one side. */
    private boolean isExtendedFromEma(double price, int barIndex) {
        if (barIndex >= emaValues.length || barIndex >= atrValues.length) return false;
        double ema = emaValues[barIndex];
        double atr = atrValues[barIndex];
        return Math.abs(price - ema) >= EXTENSION_ATR * atr;
    }

    /**
     * Look for a trendline break after P2.
     *
     * Two-step confirmation:
     * 1. Breakout candle: close crosses the trendline
     * 2. Confirmation candle: next candle breaks the breakout candle's high (bullish) or low (bearish)
     *
     * Entry = confirmation candle's breakout price (breakout candle's high/low)
     * SL = trendline value at the confirmation bar
     * Target = entry +/- AB distance
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

            boolean broken;
            if (bullish) {
                broken = close > trendlinePrice;
            } else {
                broken = close < trendlinePrice;
            }

            if (broken) {
                // Step 2: wait for next candle to confirm by breaking breakout candle's high/low
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
                        // SL = trendline value at the confirmation bar
                        double slTrendline = p1Price + slopeAbsolute * (j - p1Idx);

                        // SL 2 = median of the breakout candle (previous candle before confirmation)
                        double stopLossBreakoutCandle = (breakoutHigh + breakoutLow) / 2.0;

                        // Target = entry +/- AB distance
                        double target = bullish ? entryPrice + abDistance : entryPrice - abDistance;

                        return new TrendlineBreakoutPattern(
                                p1, p2, priorSwing, trendline, bullish,
                                abDistance, entryPrice,
                                slTrendline, stopLossBreakoutCandle,
                                target, j);
                    }
                }
                // Breakout candle found but no confirmation within 5 bars — skip
                return null;
            }
        }

        return null; // no breakout found yet
    }
}
