package com.dtech.ta;

import com.dtech.ta.BarTuple;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import java.util.ArrayList;
import java.util.List;

public class OHLCAnalyzer {

    private static final double STD_DEV_THRESHOLD = 2.0; // Standard deviations threshold

    public List<BarTuple> findSignificantLocalMaxima(BarSeries series) {
        List<BarTuple> significantMaxima = new ArrayList<>();
        double meanHigh = calculateMeanHigh(series);
        double stdDevHigh = calculateStdDevHigh(series);
        for (int i = 1; i < series.getBarCount() - 1; i++) {
            Bar currentBar = series.getBar(i);
            double currentHigh = currentBar.getHighPrice().doubleValue();
            Bar prevBar = series.getBar(i - 1);
            Bar nextBar = series.getBar(i + 1);
            double prevHigh = prevBar.getHighPrice().doubleValue();
            double nextHigh = nextBar.getHighPrice().doubleValue();

            if (currentHigh > prevHigh && currentHigh > nextHigh) {
                if (isSignificant(currentHigh, meanHigh, stdDevHigh, STD_DEV_THRESHOLD)) {
                    significantMaxima.add(new BarTuple(i, currentBar));
                }
            }
        }
        return significantMaxima;
    }

    public List<BarTuple> findSignificantLocalMinima(BarSeries series) {
        List<BarTuple> significantMinima = new ArrayList<>();
        double meanLow = calculateMeanLow(series);
        double stdDevLow = calculateStdDevLow(series);
        for (int i = 1; i < series.getBarCount() - 1; i++) {
            Bar currentBar = series.getBar(i);
            double currentLow = currentBar.getLowPrice().doubleValue();
            Bar prevBar = series.getBar(i - 1);
            Bar nextBar = series.getBar(i + 1);
            double prevLow = prevBar.getLowPrice().doubleValue();
            double nextLow = nextBar.getLowPrice().doubleValue();

            if (currentLow < prevLow && currentLow < nextLow) {
                if (isSignificant(currentLow, meanLow, stdDevLow, STD_DEV_THRESHOLD)) {
                    significantMinima.add(new BarTuple(i, currentBar));
                }
            }
        }
        return significantMinima;
    }

    private boolean isSignificant(double value, double mean, double stdDev, double threshold) {
        return Math.abs(value - mean) / stdDev >= threshold;
    }

    private double calculateMeanHigh(BarSeries series) {
        double totalHigh = 0;
        for (Bar bar : series.getBarData()) {
            totalHigh += bar.getHighPrice().doubleValue();
        }
        return totalHigh / series.getBarCount();
    }

    private double calculateMeanLow(BarSeries series) {
        double totalLow = 0;
        for (Bar bar : series.getBarData()) {
            totalLow += bar.getLowPrice().doubleValue();
        }
        return totalLow / series.getBarCount();
    }

    private double calculateStdDevHigh(BarSeries series) {
        double meanHigh = calculateMeanHigh(series);
        double sumSquaredDifferences = 0;
        for (Bar bar : series.getBarData()) {
            double diff = bar.getHighPrice().doubleValue() - meanHigh;
            sumSquaredDifferences += diff * diff;
        }
        return Math.sqrt(sumSquaredDifferences / series.getBarCount());
    }

    private double calculateStdDevLow(BarSeries series) {
        double meanLow = calculateMeanLow(series);
        double sumSquaredDifferences = 0;
        for (Bar bar : series.getBarData()) {
            double diff = bar.getLowPrice().doubleValue() - meanLow;
            sumSquaredDifferences += diff * diff;
        }
        return Math.sqrt(sumSquaredDifferences / series.getBarCount());
    }
}
