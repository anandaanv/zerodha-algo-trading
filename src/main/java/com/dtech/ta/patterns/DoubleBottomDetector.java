package com.dtech.ta.patterns;

import com.dtech.ta.BarTuple;
import com.dtech.ta.TrendLineCalculated;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.util.ArrayList;
import java.util.List;

public class DoubleBottomDetector {

    private final BarSeries series;
    private final ClosePriceIndicator closePrice;
    private final RSIIndicator rsi;

    public DoubleBottomDetector(BarSeries series) {
        this.series = series;
        this.closePrice = new ClosePriceIndicator(series);
        this.rsi = new RSIIndicator(closePrice, 14);
    }

    public List<TrendLineCalculated> detectDoubleBottoms(int windowSize) {
        List<TrendLineCalculated> trendlines = new ArrayList<>();

        for (int i = windowSize; i < series.getBarCount(); i++) {
            // Step 1: Find a lower high (H1)
            int high1Index = findLowerHigh(i, windowSize, series.getBar(i).getHighPrice().doubleValue());
            if (high1Index == -1) continue;

            // Step 2: Find lower low prior to that high (L1)
            int low1Index = findLowerLowBeforeHigh(high1Index, windowSize);
            if (low1Index == -1) continue;

            // Step 4: Find another higher high before H1 (H2)
            int high2Index = findHigherHighBeforeHigh(high1Index, windowSize);
            if (high2Index == -1) continue;

            // Step 3: Find recent low (L2) and check retracement
            int low2Index = findLowestPriceBetweenTwoHighs(high1Index, high2Index);
            if (low2Index == -1 || !isRetracementValid(low1Index, high1Index, low2Index)) continue;

            // Step 5: Draw trendline from H2 to H1
            TrendLineCalculated trendline = drawTrendline(high2Index, high1Index);

            // Step 6: Check if trendline is broken and retested with entry criteria
            if (isTrendlineBrokenAndRetested(trendline, low2Index)) {
                trendlines.add(trendline);
            }
        }

        return trendlines;
    }

    private int findLowestPriceBetweenTwoHighs(int high1Index1, int highIndex2) {
        double lowest = series.getBar(high1Index1).getLowPrice().doubleValue();
        int idx = -1;
        for (int i = highIndex2 + 5; i <high1Index1; i++) {
            if(series.getBar(i).getLowPrice().doubleValue() < lowest) {
                idx = i;
                lowest = series.getBar(i).getLowPrice().doubleValue();
            }
        }
        return idx;
    }

    private int findLowerHigh(int endIndex, int windowSize, double currentPrice) {
        double previousHigh = Double.MAX_VALUE;
        int highIndex = -1;
        for (int i = endIndex - 5; i >= Math.max(0, endIndex - windowSize); i--) {  // Skip the first 5 candles
            double currentHigh = series.getBar(i).getHighPrice().doubleValue();
            if (currentHigh < previousHigh && currentHigh > currentPrice) {
                previousHigh = currentHigh;
                highIndex = i;
            }
        }
        return highIndex;
    }

    private int findLowerLowBeforeHigh(int highIndex, int windowSize) {
        double previousLow = Double.MAX_VALUE;
        int lowIndex = -1;
        for (int i = highIndex - 6; i >= Math.max(0, highIndex - windowSize); i--) {  // Skip the first 5 candles
            double currentLow = series.getBar(i).getLowPrice().doubleValue();
            if (currentLow < previousLow) {
                previousLow = currentLow;
                lowIndex = i;
            }
        }
        return lowIndex;
    }

    private int findRecentLowAfterLow1(int low1Index, int windowSize) {
        double lowestPrice = Double.MAX_VALUE;
        int lowIndex = -1;
        for (int i = low1Index + 6; i <= Math.min(series.getBarCount() - 1, low1Index + windowSize); i++) {  // Skip the first 5 candles
            double currentLow = series.getBar(i).getLowPrice().doubleValue();
            if (currentLow < lowestPrice) {
                lowestPrice = currentLow;
                lowIndex = i;
            }
        }
        return lowIndex;
    }

    private boolean isRetracementValid(int low1Index, int high1Index, int low2Index) {
        double highPrice = series.getBar(high1Index).getHighPrice().doubleValue();
        double lowPrice = series.getBar(low2Index).getLowPrice().doubleValue();
        double low1Price = series.getBar(low1Index).getLowPrice().doubleValue();
        Bar b1 = series.getBar(high1Index), b2 = series.getBar(low1Index), b3 = series.getBar(low2Index);
        double retracement = (highPrice -lowPrice) / (highPrice - low1Price) * 100;
        return retracement >= 0.5 && retracement <= 1.10;
    }

    private int findHigherHighBeforeHigh(int high1Index, int windowSize) {
        double previousHigh = Double.MIN_VALUE;
        int highIndex = -1;
        for (int i = high1Index - 6; i >= Math.max(0, high1Index - windowSize); i--) {  // Skip the first 5 candles
            double currentHigh = series.getBar(i).getHighPrice().doubleValue();
            if (currentHigh > previousHigh && currentHigh > series.getBar(high1Index).getHighPrice().doubleValue()) {
                previousHigh = currentHigh;
                highIndex = i;
            }
        }
        return highIndex;
    }

    private TrendLineCalculated drawTrendline(int high2Index, int high1Index) {
        double slope = calculateSlope(high2Index, high1Index);
        double intercept = calculateIntercept(high2Index, slope);

        List<BarTuple> points = new ArrayList<>();
        for (int k = high2Index; k <= high1Index; k++) {
            points.add(new BarTuple(k, series.getBar(k)));
        }

        return new TrendLineCalculated(series, slope, intercept, points, false);
    }

    private boolean isTrendlineBrokenAndRetested(TrendLineCalculated trendline, int low2Index) {
        for (int i = low2Index + 1; i < series.getBarCount(); i++) {
            double closePriceValue = closePrice.getValue(i).doubleValue();
            double trendlineValue = trendline.getPriceAt(i);

            // Check if the trendline is broken
            if (closePriceValue > trendlineValue) {
                // Check if RSI is above 40
                if (rsi.getValue(i).doubleValue() > 40) {
                    return true;
                }
            }
        }

        return false;
    }

    private double calculateSlope(int startIndex, int endIndex) {
        double y1 = series.getBar(startIndex).getHighPrice().doubleValue();
        double y2 = series.getBar(endIndex).getHighPrice().doubleValue();
        int xDiff = endIndex - startIndex;
        return (y2 - y1) / xDiff;
    }

    private double calculateIntercept(int index, double slope) {
        double price = series.getBar(index).getHighPrice().doubleValue();
        return price - slope * index;
    }
}
