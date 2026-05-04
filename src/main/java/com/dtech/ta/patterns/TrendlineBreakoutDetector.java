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

    private static final int DEFAULT_EMA_PERIOD_FAST = 100;
    private static final int DEFAULT_EMA_PERIOD_SLOW = 200;
    private static final double EMA_ZONE_ATR = 1.0;
    private static final double EXTENSION_ATR = 1.5;
    private static final int DEFAULT_MIN_GAP_BARS = 100;  // default; pass TF-appropriate value via constructor

    private static final double DEFAULT_TARGET_FRACTION = 1.0;  // full AB=CD; pass 0.3-0.5 for "next S/R" target

    private final BarSeries series;
    private final double[] ema100;
    private final double[] ema200;
    private final double[] atrValues;
    private final int minGapBars;
    private final double targetFraction;

    public TrendlineBreakoutDetector(BarSeries series, double[] atrValues) {
        this(series, atrValues, DEFAULT_MIN_GAP_BARS, DEFAULT_TARGET_FRACTION, DEFAULT_EMA_PERIOD_FAST, DEFAULT_EMA_PERIOD_SLOW);
    }

    public TrendlineBreakoutDetector(BarSeries series, double[] atrValues, int minGapBars) {
        this(series, atrValues, minGapBars, DEFAULT_TARGET_FRACTION, DEFAULT_EMA_PERIOD_FAST, DEFAULT_EMA_PERIOD_SLOW);
    }

    public TrendlineBreakoutDetector(BarSeries series, double[] atrValues, int minGapBars, double targetFraction) {
        this(series, atrValues, minGapBars, targetFraction, DEFAULT_EMA_PERIOD_FAST, DEFAULT_EMA_PERIOD_SLOW);
    }

    public TrendlineBreakoutDetector(BarSeries series, double[] atrValues, int minGapBars,
                                      double targetFraction, int emaFastPeriod, int emaSlowPeriod) {
        this.minGapBars = minGapBars;
        this.targetFraction = targetFraction;
        this.series = series;
        this.atrValues = atrValues;

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        EMAIndicator emaFast = new EMAIndicator(closePrice, emaFastPeriod);
        EMAIndicator emaSlow = new EMAIndicator(closePrice, emaSlowPeriod);

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
        int totalPivots = pivots.size();
        int p1InEmaZone = 0, p1WithExtendedPrior = 0;
        int p2WithMinGap = 0, p2InEmaZone = 0, p2WithExtendedPrior = 0;
        int virginPasses = 0, breakoutFound = 0;

        for (int i = 0; i < pivots.size(); i++) {
            ZigZagPoint candidateP1 = pivots.get(i);
            Integer p1Idx = tsToIdx.get(candidateP1.getTimestamp());
            if (p1Idx == null || p1Idx >= atrValues.length) continue;

            // P1 must be in EMA zone of EITHER 100 or 200 EMA
            boolean p1InEma100 = isInEmaZone(candidateP1.getValue(), p1Idx, ema100);
            boolean p1InEma200 = isInEmaZone(candidateP1.getValue(), p1Idx, ema200);
            if (!p1InEma100 && !p1InEma200) continue;
            p1InEmaZone++;

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
            p1WithExtendedPrior++;

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
                if (p2Idx - p1Idx < minGapBars) continue;
                p2WithMinGap++;

                // P2 must be in EMA zone of either EMA
                boolean p2InEma100 = isInEmaZone(candidateP2.getValue(), p2Idx, ema100);
                boolean p2InEma200 = isInEmaZone(candidateP2.getValue(), p2Idx, ema200);
                if (!p2InEma100 && !p2InEma200) continue;
                p2InEmaZone++;

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
                p2WithExtendedPrior++;

                // Build trendline
                double p1Price = candidateP1.getValue();
                double p2Price = candidateP2.getValue();
                double slope = (p2Price - p1Price) / (double)(p2Idx - p1Idx);

                // VIRGIN TRENDLINE CHECK — no candle close has crossed the trendline between P1 and P2
                if (!isVirginTrendlinePivots(pivots, p1Idx, p2Idx, p1Price, slope, bullish, tsToIdx)) continue;
                virginPasses++;

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
                    breakoutFound++;
                }
                break; // first valid P2 only
            }
        }

        org.slf4j.LoggerFactory.getLogger(TrendlineBreakoutDetector.class).info(
                "[TLB-detect] pivots={} p1InEmaZone={} p1WithExtPrior={} p2MinGap={} p2InEmaZone={} p2ExtPrior={} virgin={} breakout={}",
                totalPivots, p1InEmaZone, p1WithExtendedPrior, p2WithMinGap, p2InEmaZone, p2WithExtendedPrior, virginPasses, breakoutFound);
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

    /**
     * Pivot-based virginity check: trendline is virgin if no opposite-side
     * pivot between P1 and P2 violates the line.
     *
     * For bullish (HIGHs trendline): no HIGH pivot in (p1Idx, p2Idx) is ABOVE the line at its bar.
     * For bearish (LOWs trendline): no LOW pivot in (p1Idx, p2Idx) is BELOW the line at its bar.
     *
     * Skips P1 and P2 themselves (they're on the line by construction).
     */
    private boolean isVirginTrendlinePivots(List<ZigZagPoint> pivots, int p1Idx, int p2Idx,
                                              double p1Price, double slope, boolean bullish,
                                              Map<Instant, Integer> tsToIdx) {
        ZigZagPoint.Type targetType = bullish ? ZigZagPoint.Type.HIGH : ZigZagPoint.Type.LOW;
        for (ZigZagPoint pv : pivots) {
            if (pv.getType() != targetType) continue;
            Integer pIdx = tsToIdx.get(pv.getTimestamp());
            if (pIdx == null || pIdx <= p1Idx || pIdx >= p2Idx) continue;
            double trendlineAt = p1Price + slope * (pIdx - p1Idx);
            if (bullish && pv.getValue() > trendlineAt) return false;
            if (!bullish && pv.getValue() < trendlineAt) return false;
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
                        double scaledAB = abDistance * targetFraction;
                        double target = bullish ? entryPrice + scaledAB : entryPrice - scaledAB;

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
