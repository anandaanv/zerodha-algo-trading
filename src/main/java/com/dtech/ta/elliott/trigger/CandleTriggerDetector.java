package com.dtech.ta.elliott.trigger;

import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.List;

@Service
public class CandleTriggerDetector {

    /**
     * Scan the last N bars for candle trigger patterns.
     */
    public List<CandleTrigger> detect(BarSeries barSeries, int lookback) {
        List<CandleTrigger> triggers = new ArrayList<>();
        if (barSeries == null || lookback <= 0) return triggers;

        int endIndex = barSeries.getEndIndex();
        int startIndex = Math.max(barSeries.getBeginIndex(), endIndex - lookback + 1);

        for (int i = startIndex; i <= endIndex; i++) {
            Bar bar = barSeries.getBar(i);
            double open  = bar.getOpenPrice().doubleValue();
            double high  = bar.getHighPrice().doubleValue();
            double low   = bar.getLowPrice().doubleValue();
            double close = bar.getClosePrice().doubleValue();

            double range         = high - low;
            double bodySize      = Math.abs(close - open);
            double bodyRatio     = range > 0 ? bodySize / range : 0;
            double upperWick     = high - Math.max(open, close);
            double lowerWick     = Math.min(open, close) - low;
            double closeLocation = range > 0 ? (close - low) / range : 0.5;

            List<String> reasons = new ArrayList<>();

            // HAMMER
            if (bodyRatio < 0.35 && lowerWick >= 0.55 * range && closeLocation > 0.55) {
                reasons.add("bodyRatio=" + round2(bodyRatio));
                reasons.add("lowerWick=" + round2(lowerWick / range * 100) + "% of range");
                reasons.add("closeLocation=" + round2(closeLocation));
                triggers.add(CandleTrigger.builder()
                        .type(TriggerType.HAMMER)
                        .barIndex(i)
                        .triggerPrice(close)
                        .bullish(true)
                        .reasons(new ArrayList<>(reasons))
                        .build());
                continue;
            }

            // SHOOTING STAR
            if (bodyRatio < 0.35 && upperWick >= 0.55 * range && closeLocation < 0.45) {
                reasons.add("bodyRatio=" + round2(bodyRatio));
                reasons.add("upperWick=" + round2(upperWick / range * 100) + "% of range");
                reasons.add("closeLocation=" + round2(closeLocation));
                triggers.add(CandleTrigger.builder()
                        .type(TriggerType.SHOOTING_STAR)
                        .barIndex(i)
                        .triggerPrice(close)
                        .bullish(false)
                        .reasons(new ArrayList<>(reasons))
                        .build());
                continue;
            }

            // ENGULFING (requires previous bar)
            if (i > barSeries.getBeginIndex()) {
                Bar prev = barSeries.getBar(i - 1);
                double prevOpen  = prev.getOpenPrice().doubleValue();
                double prevClose = prev.getClosePrice().doubleValue();

                boolean prevBearish = prevClose < prevOpen;
                boolean currBullish = close > open;
                boolean prevBullish = prevClose > prevOpen;
                boolean currBearish = close < open;

                // BULLISH ENGULFING: prev down, curr up and engulfs prev body
                if (prevBearish && currBullish && close > prevOpen && open < prevClose) {
                    triggers.add(CandleTrigger.builder()
                            .type(TriggerType.BULLISH_ENGULFING)
                            .barIndex(i)
                            .triggerPrice(close)
                            .bullish(true)
                            .reasons(List.of("engulfs_prev_bearish_body"))
                            .build());
                    continue;
                }

                // BEARISH ENGULFING: prev up, curr down and engulfs prev body
                if (prevBullish && currBearish && close < prevOpen && open > prevClose) {
                    triggers.add(CandleTrigger.builder()
                            .type(TriggerType.BEARISH_ENGULFING)
                            .barIndex(i)
                            .triggerPrice(close)
                            .bullish(false)
                            .reasons(List.of("engulfs_prev_bullish_body"))
                            .build());
                    continue;
                }
            }

            // LONG LOWER WICK REJECTION (not a strict hammer)
            if (lowerWick >= 0.65 * range && bodyRatio >= 0.35) {
                triggers.add(CandleTrigger.builder()
                        .type(TriggerType.LONG_LOWER_WICK_REJECTION)
                        .barIndex(i)
                        .triggerPrice(close)
                        .bullish(true)
                        .reasons(List.of("lowerWick=" + round2(lowerWick / range * 100) + "% of range"))
                        .build());
                continue;
            }

            // LONG UPPER WICK REJECTION (not a strict shooting star)
            if (upperWick >= 0.65 * range && bodyRatio >= 0.35) {
                triggers.add(CandleTrigger.builder()
                        .type(TriggerType.LONG_UPPER_WICK_REJECTION)
                        .barIndex(i)
                        .triggerPrice(close)
                        .bullish(false)
                        .reasons(List.of("upperWick=" + round2(upperWick / range * 100) + "% of range"))
                        .build());
            }
        }

        return triggers;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
