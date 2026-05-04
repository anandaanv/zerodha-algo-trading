package com.dtech.kitecon.backtest;

import org.springframework.stereotype.Component;
import org.ta4j.core.Bar;
import java.util.List;

@Component
public class CandlestickPatternDetector {

    public enum CandlePattern {
        HAMMER, INVERTED_HAMMER, BULLISH_ENGULFING, PIERCING_LINE, MORNING_STAR, DOJI,
        SHOOTING_STAR, BEARISH_ENGULFING, DARK_CLOUD_COVER, EVENING_STAR,
        NONE
    }

    public record PatternResult(CandlePattern pattern, double breakoutLevel, boolean bullish) {}

    // Detect bullish reversal pattern ending at barIndex
    // Returns NONE if no pattern found
    public PatternResult detectBullish(List<Bar> bars, int barIndex) {
        if (barIndex < 0 || barIndex >= bars.size()) return new PatternResult(CandlePattern.NONE, 0, true);
        Bar cur = bars.get(barIndex);
        double o = cur.getOpenPrice().doubleValue();
        double h = cur.getHighPrice().doubleValue();
        double l = cur.getLowPrice().doubleValue();
        double c = cur.getClosePrice().doubleValue();
        double body = Math.abs(c - o);
        double range = h - l;
        if (range == 0) return new PatternResult(CandlePattern.NONE, 0, true);

        // Hammer: small body at top, lower shadow >= 2x body, upper shadow <= 0.1x range
        double lowerShadow = Math.min(o, c) - l;
        double upperShadow = h - Math.max(o, c);
        if (body <= range * 0.35 && lowerShadow >= body * 2.0 && upperShadow <= range * 0.15 && c >= o) {
            return new PatternResult(CandlePattern.HAMMER, h, true);
        }

        // Inverted Hammer: small body at bottom, upper shadow >= 2x body
        if (body <= range * 0.35 && upperShadow >= body * 2.0 && lowerShadow <= range * 0.15) {
            return new PatternResult(CandlePattern.INVERTED_HAMMER, h, true);
        }

        // Doji: open ≈ close (body < 10% of range)
        if (body <= range * 0.10) {
            return new PatternResult(CandlePattern.DOJI, h, true);
        }

        // Two-candle patterns
        if (barIndex >= 1) {
            Bar prev = bars.get(barIndex - 1);
            double po = prev.getOpenPrice().doubleValue();
            double ph = prev.getHighPrice().doubleValue();
            double pl = prev.getLowPrice().doubleValue();
            double pc = prev.getClosePrice().doubleValue();

            // Bullish Engulfing: prev bearish, cur bullish, cur body engulfs prev body
            if (pc < po && c > o && c > po && o < pc) {
                return new PatternResult(CandlePattern.BULLISH_ENGULFING, h, true);
            }

            // Piercing Line: prev bearish, cur opens below prev low, closes above midpoint of prev body
            double prevMid = (po + pc) / 2.0;
            if (pc < po && c > o && o < pl && c > prevMid && c < po) {
                return new PatternResult(CandlePattern.PIERCING_LINE, ph, true); // breakout = prev candle high
            }
        }

        // Three-candle: Morning Star
        if (barIndex >= 2) {
            Bar prev2 = bars.get(barIndex - 2);
            Bar prev1 = bars.get(barIndex - 1);
            double p2o = prev2.getOpenPrice().doubleValue();
            double p2c = prev2.getClosePrice().doubleValue();
            double p1o = prev1.getOpenPrice().doubleValue();
            double p1c = prev1.getClosePrice().doubleValue();
            double p1range = prev1.getHighPrice().doubleValue() - prev1.getLowPrice().doubleValue();
            double p1body = Math.abs(p1c - p1o);
            // prev2 bearish, prev1 small body (star), cur bullish closing into prev2 body
            if (p2c < p2o && p1body <= p1range * 0.35 && c > o && c > (p2o + p2c) / 2.0) {
                return new PatternResult(CandlePattern.MORNING_STAR, h, true);
            }
        }

        return new PatternResult(CandlePattern.NONE, 0, true);
    }

    // Detect bearish reversal pattern ending at barIndex
    public PatternResult detectBearish(List<Bar> bars, int barIndex) {
        if (barIndex < 0 || barIndex >= bars.size()) return new PatternResult(CandlePattern.NONE, 0, false);
        Bar cur = bars.get(barIndex);
        double o = cur.getOpenPrice().doubleValue();
        double h = cur.getHighPrice().doubleValue();
        double l = cur.getLowPrice().doubleValue();
        double c = cur.getClosePrice().doubleValue();
        double body = Math.abs(c - o);
        double range = h - l;
        if (range == 0) return new PatternResult(CandlePattern.NONE, 0, false);

        double lowerShadow = Math.min(o, c) - l;
        double upperShadow = h - Math.max(o, c);

        // Shooting Star
        if (body <= range * 0.35 && upperShadow >= body * 2.0 && lowerShadow <= range * 0.15 && c <= o) {
            return new PatternResult(CandlePattern.SHOOTING_STAR, l, false);
        }

        // Doji at high
        if (body <= range * 0.10) {
            return new PatternResult(CandlePattern.DOJI, l, false);
        }

        if (barIndex >= 1) {
            Bar prev = bars.get(barIndex - 1);
            double po = prev.getOpenPrice().doubleValue();
            double ph = prev.getHighPrice().doubleValue();
            double pc = prev.getClosePrice().doubleValue();
            double pl = prev.getLowPrice().doubleValue();

            // Bearish Engulfing
            if (pc > po && c < o && c < po && o > pc) {
                return new PatternResult(CandlePattern.BEARISH_ENGULFING, l, false);
            }

            // Dark Cloud Cover
            double prevMid = (po + pc) / 2.0;
            if (pc > po && c < o && o > ph && c < prevMid && c > po) {
                return new PatternResult(CandlePattern.DARK_CLOUD_COVER, pl, false); // breakout = prev candle low
            }
        }

        if (barIndex >= 2) {
            Bar prev2 = bars.get(barIndex - 2);
            Bar prev1 = bars.get(barIndex - 1);
            double p2o = prev2.getOpenPrice().doubleValue();
            double p2c = prev2.getClosePrice().doubleValue();
            double p1o = prev1.getOpenPrice().doubleValue();
            double p1c = prev1.getClosePrice().doubleValue();
            double p1range = prev1.getHighPrice().doubleValue() - prev1.getLowPrice().doubleValue();
            double p1body = Math.abs(p1c - p1o);
            // Evening Star
            if (p2c > p2o && p1body <= p1range * 0.35 && c < o && c < (p2o + p2c) / 2.0) {
                return new PatternResult(CandlePattern.EVENING_STAR, l, false);
            }
        }

        return new PatternResult(CandlePattern.NONE, 0, false);
    }
}
