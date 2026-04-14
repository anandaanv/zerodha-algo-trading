package com.dtech.ta.patterns;

import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.ta.TrendLineCalculated;

/**
 * Trendline Breakout pattern anchored to the 100 EMA + ATR zone.
 *
 * Pivot 1 & Pivot 2 are ZigZag pivots that touch the 100 EMA ATR zone.
 * Direction is determined by where price came from before P1:
 *   - From above EMA → bearish (short on trendline break below)
 *   - From below EMA → bullish (long on trendline break above)
 * Target = AB measured move (|priorSwing - P1| projected from breakout).
 */
public class TrendlineBreakoutPattern {
    private final ZigZagPoint pivot1;          // first EMA-zone touch
    private final ZigZagPoint pivot2;          // second EMA-zone touch
    private final ZigZagPoint priorSwing;      // the swing high/low before P1 (the "A" in AB)
    private final TrendLineCalculated trendline; // line connecting P1 and P2
    private final boolean bullish;
    private final double abDistance;           // |priorSwing.value - pivot1.value|
    private final double breakoutLevel;        // trendline value at breakout bar
    private final double stopLossTrendline;    // SL at trendline / P2 level
    private final double stopLossBreakoutCandle; // SL at median (H+L)/2 of breakout candle
    private final double target;               // breakoutLevel +/- abDistance
    private final int breakoutBarIndex;        // index of the bar that broke the trendline

    public TrendlineBreakoutPattern(ZigZagPoint pivot1, ZigZagPoint pivot2, ZigZagPoint priorSwing,
                                     TrendLineCalculated trendline, boolean bullish,
                                     double abDistance, double breakoutLevel,
                                     double stopLossTrendline, double stopLossBreakoutCandle,
                                     double target, int breakoutBarIndex) {
        this.pivot1 = pivot1;
        this.pivot2 = pivot2;
        this.priorSwing = priorSwing;
        this.trendline = trendline;
        this.bullish = bullish;
        this.abDistance = abDistance;
        this.breakoutLevel = breakoutLevel;
        this.stopLossTrendline = stopLossTrendline;
        this.stopLossBreakoutCandle = stopLossBreakoutCandle;
        this.target = target;
        this.breakoutBarIndex = breakoutBarIndex;
    }

    public ZigZagPoint getPivot1() { return pivot1; }
    public ZigZagPoint getPivot2() { return pivot2; }
    public ZigZagPoint getPriorSwing() { return priorSwing; }
    public TrendLineCalculated getTrendline() { return trendline; }
    public boolean isBullish() { return bullish; }
    public double getAbDistance() { return abDistance; }
    public double getBreakoutLevel() { return breakoutLevel; }
    public double getStopLossTrendline() { return stopLossTrendline; }
    public double getStopLossBreakoutCandle() { return stopLossBreakoutCandle; }
    public double getTarget() { return target; }
    public int getBreakoutBarIndex() { return breakoutBarIndex; }
}
