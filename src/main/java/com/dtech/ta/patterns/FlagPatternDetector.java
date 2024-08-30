package com.dtech.ta.patterns;

import com.dtech.ta.BarTuple;
import com.dtech.ta.TrendLineCalculated;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.util.ArrayList;
import java.util.List;

public class FlagPatternDetector {

    private final BarSeries series;
    private final IndicatorCalculator indicatorCalculator;

    public FlagPatternDetector(BarSeries series, IndicatorCalculator indicatorCalculator) {
        this.series = series;
        this.indicatorCalculator = indicatorCalculator;
    }

    public List<FlagPattern> detectFlags(int windowSize, double maxRangePercentage) {
        List<FlagPattern> flags = new ArrayList<>();

        // Step 1: Detect poles using MACD and RSI
        List<Integer> poles = detectPoles();

        // Step 2: After detecting poles, find flags associated with each pole
        for (Integer poleIndex : poles) {
            if (poleIndex + windowSize < series.getBarCount()) {
                if (isRangeboundAndDecreasing(poleIndex, windowSize, maxRangePercentage)) {
                    TrendLineCalculated highTrendline = findTrendline(poleIndex, poleIndex + windowSize - 1, true);
                    TrendLineCalculated lowTrendline = findTrendline(poleIndex, poleIndex + windowSize - 1, false);

                    if (highTrendline != null && lowTrendline != null && isValidFlag(highTrendline, lowTrendline)) {
                        flags.add(new FlagPattern(poleIndex, poleIndex + windowSize - 1, highTrendline, lowTrendline));
                    }
                }
            }
        }
        return flags;
    }

    private List<Integer> detectPoles() {
        List<Integer> poleIndices = new ArrayList<>();

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        MACDIndicator macd = new MACDIndicator(closePrice, 12, 26);
        EMAIndicator macdSignal = new EMAIndicator(macd, 9);
        RSIIndicator rsi = new RSIIndicator(closePrice, 14);

        for (int i = 1; i < series.getBarCount(); i++) {
            double macdValue = macd.getValue(i).doubleValue();
            double macdSignalValue = macdSignal.getValue(i).doubleValue();
            double previousMacdValue = macd.getValue(i - 1).doubleValue();
            double rsiValue = rsi.getValue(i).doubleValue();

            // Detect a strong upward pole
            if (macdValue > macdSignalValue && previousMacdValue <= macdSignalValue && rsiValue > 70) {
                poleIndices.add(i);
            }
            // Detect a strong downward pole
            else if (macdValue < macdSignalValue && previousMacdValue >= macdSignalValue && rsiValue < 30) {
                poleIndices.add(i);
            }
        }

        return poleIndices;
    }

    private boolean isRangeboundAndDecreasing(int startIndex, int windowSize, double maxRangePercentage) {
        double high = series.getBar(startIndex).getHighPrice().doubleValue();
        double low = series.getBar(startIndex).getLowPrice().doubleValue();

        double previousAverage = indicatorCalculator.getIndicatorAverage(startIndex);
        for (int i = startIndex + 1; i < startIndex + windowSize; i++) {
            high = Math.max(high, series.getBar(i).getHighPrice().doubleValue());
            low = Math.min(low, series.getBar(i).getLowPrice().doubleValue());

            double currentAverage = indicatorCalculator.getIndicatorAverage(i);
            if (currentAverage >= previousAverage) {
                return false;  // Indicator average is not decreasing
            }
            previousAverage = currentAverage;
        }

        double rangePercentage = (high - low) / low;
        return rangePercentage <= maxRangePercentage;
    }

    private TrendLineCalculated findTrendline(int startIndex, int endIndex, boolean useHighs) {
        for (int i = startIndex; i < endIndex; i++) {
            for (int j = i + 1; j <= endIndex; j++) {
                double slope = calculateSlope(i, j, useHighs);
                double intercept = calculateIntercept(i, slope, useHighs);

                List<BarTuple> points = new ArrayList<>();
                for (int k = i; k <= j; k++) {
                    double price = useHighs ? series.getBar(k).getHighPrice().doubleValue() : series.getBar(k).getLowPrice().doubleValue();
                    double expectedPrice = slope * (k - i) + intercept;
                    if (Math.abs(price - expectedPrice) > price * 0.01) {
                        break;
                    }
                    points.add(new BarTuple(k, series.getBar(k)));
                }

                if (points.size() >= 3) {  // Ensure at least 3 points are needed for a valid trendline
                    return new TrendLineCalculated(series, slope, intercept, points, !useHighs);
                }
            }
        }
        return null;
    }

    private boolean isValidFlag(TrendLineCalculated highTrendline, TrendLineCalculated lowTrendline) {
        // Logic to confirm that the high and low trendlines form a valid flag pattern
        return true;  // Placeholder: replace with actual logic
    }

    private double calculateSlope(int startIndex, int endIndex, boolean useHighs) {
        double y1 = useHighs ? series.getBar(startIndex).getHighPrice().doubleValue() : series.getBar(startIndex).getLowPrice().doubleValue();
        double y2 = useHighs ? series.getBar(endIndex).getHighPrice().doubleValue() : series.getBar(endIndex).getLowPrice().doubleValue();
        return (y2 - y1) / (endIndex - startIndex);
    }

    private double calculateIntercept(int startIndex, double slope, boolean useHighs) {
        double y1 = useHighs ? series.getBar(startIndex).getHighPrice().doubleValue() : series.getBar(startIndex).getLowPrice().doubleValue();
        return y1 - slope * startIndex;
    }
}
