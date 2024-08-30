package com.dtech.ta;

import org.ta4j.core.BarSeries;
import java.util.*;

public class TrendAnalysis {

    private final BarSeries series;

    public TrendAnalysis(BarSeries series) {
        this.series = series;
    }

    // Method to get accurate local maxima and minima based on Fibonacci levels
    public Map<String, List<BarTuple>> getAccurateLocalMaximaAndMinima(int windowSize, double tolerance) {
        List<BarTuple> localMaxima = findLocalMaxima(windowSize);
        List<BarTuple> localMinima = findLocalMinima(windowSize);

        List<BarTuple> filteredMaxima = filterByFibonacciLevels(localMaxima, true, tolerance);
        List<BarTuple> filteredMinima = filterByFibonacciLevels(localMinima, false, tolerance);

        Map<String, List<BarTuple>> result = new HashMap<>();
        result.put("Maxima", localMaxima);
        result.put("Minima", localMinima);

        return result;
    }

    public List<BarTuple> findLocalMaxima(int windowSize) {
        List<BarTuple> localMaxima = new ArrayList<>();
        for (int i = windowSize; i < series.getBarCount() - windowSize; i++) {
            boolean isMaxima = true;
            for (int j = i - windowSize; j <= i + windowSize; j++) {
                if (series.getBar(j).getHighPrice().doubleValue() > series.getBar(i).getHighPrice().doubleValue()) {
                    isMaxima = false;
                    break;
                }
            }
            if (isMaxima) {
                localMaxima.add(new BarTuple(i, series.getBar(i), OHLC.H));
            }
        }
        return localMaxima;
    }

    public List<BarTuple> findLocalMinima(int windowSize) {
        List<BarTuple> localMinima = new ArrayList<>();
        for (int i = windowSize; i < series.getBarCount() - windowSize; i++) {
            boolean isMinima = true;
            for (int j = i - windowSize; j <= i + windowSize; j++) {
                if (series.getBar(j).getLowPrice().doubleValue() < series.getBar(i).getLowPrice().doubleValue()) {
                    isMinima = false;
                    break;
                }
            }
            if (isMinima) {
                localMinima.add(new BarTuple(i, series.getBar(i), OHLC.L));
            }
        }
        return localMinima;
    }

    // Step 2: Filter by Fibonacci Levels
    private List<BarTuple> filterByFibonacciLevels(List<BarTuple> extremes, boolean isMaxima, double tolerance) {
        List<BarTuple> filteredExtremes = new ArrayList<>();
        double high = getRecentHigh();
        double low = getRecentLow();
        Map<String, Double> fibLevels = calculateFibonacciLevels(high, low);

        for (BarTuple extreme : extremes) {
            double price = isMaxima ? extreme.getValue() : extreme.getBar().getLowPrice().doubleValue();
            if (isFibonacciLevel(price, fibLevels, tolerance)) {
                filteredExtremes.add(extreme);
            }
        }

        return filteredExtremes;
    }

    private double getRecentHigh() {
        double high = Double.MIN_VALUE;
        for (int i = 0; i < series.getBarCount(); i++) {
            high = Math.max(high, series.getBar(i).getHighPrice().doubleValue());
        }
        return high;
    }

    private double getRecentLow() {
        double low = Double.MAX_VALUE;
        for (int i = 0; i < series.getBarCount(); i++) {
            low = Math.min(low, series.getBar(i).getLowPrice().doubleValue());
        }
        return low;
    }

    private Map<String, Double> calculateFibonacciLevels(double high, double low) {
        Map<String, Double> fibLevels = new HashMap<>();
        double diff = high - low;

        fibLevels.put("23.6%", high - 0.236 * diff);
        fibLevels.put("38.2%", high - 0.382 * diff);
        fibLevels.put("50%", high - 0.5 * diff);
        fibLevels.put("61.8%", high - 0.618 * diff);
        fibLevels.put("78.6%", high - 0.786 * diff);

        fibLevels.put("127.2%", high + 0.272 * diff);
        fibLevels.put("161.8%", high + 0.618 * diff);
        fibLevels.put("261.8%", high + 1.618 * diff);

        return fibLevels;
    }

    private boolean isFibonacciLevel(double price, Map<String, Double> fibLevels, double tolerance) {
        for (Double level : fibLevels.values()) {
            if (Math.abs(price - level) <= tolerance) {
                return true;
            }
        }
        return false;
    }
}
