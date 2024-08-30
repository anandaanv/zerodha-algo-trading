package com.dtech.ta.trendline;

import com.dtech.ta.OHLC;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.List;

public class Util {

    // Find the average distance between High and Low price in a set of candles
    public static double avgCandleRange(BarSeries barSeries) {
        return Math.max(barSeries.getBarData().stream()
                .mapToDouble(bar -> bar.getHighPrice().doubleValue() - bar.getLowPrice().doubleValue())
                .average().orElse(0.0), 0.01);
    }

    // Find mean in a list of doubles
    public static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    // Find the set of maximums or minimums in a series with tolerance
    public static List<Integer> findMaxsOrMinsInSeries(BarSeries barSeries, OHLC ohlcType, String kind, double toleranceThreshold) {
        List<Integer> isolatedGlobals = new ArrayList<>();
        double globalMaxOrMin;

        // Determine if we are looking for max or min
        if (kind.equals("max")) {
            globalMaxOrMin = barSeries.getBarData().stream()
                    .mapToDouble(bar -> getValueByOHLC(bar, ohlcType))
                    .max().orElseThrow();
        } else {
            globalMaxOrMin = barSeries.getBarData().stream()
                    .mapToDouble(bar -> getValueByOHLC(bar, ohlcType))
                    .min().orElseThrow();
        }

        // Use a for-loop to iterate over the bars and find matches
        for (int i = 0; i < barSeries.getBarCount(); i++) {
            Bar bar = barSeries.getBar(i);
            double value = getValueByOHLC(bar, ohlcType);

            if (value == globalMaxOrMin || Math.abs(globalMaxOrMin - value) < toleranceThreshold) {
                isolatedGlobals.add(i);  // Add the index directly
            }
        }

        return isolatedGlobals;
    }

    // Get value by OHLC
    private static double getValueByOHLC(Bar bar, OHLC ohlcType) {
        switch (ohlcType) {
            case O:
                return bar.getOpenPrice().doubleValue();
            case H:
                return bar.getHighPrice().doubleValue();
            case L:
                return bar.getLowPrice().doubleValue();
            case C:
                return bar.getClosePrice().doubleValue();
            default:
                throw new IllegalStateException("Unexpected OHLC type: " + ohlcType);
        }
    }
}
