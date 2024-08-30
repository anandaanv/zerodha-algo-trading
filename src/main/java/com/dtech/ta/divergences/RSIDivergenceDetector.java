package com.dtech.ta.divergences;

import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import com.dtech.ta.BarTuple;

import java.util.ArrayList;
import java.util.List;

public class RSIDivergenceDetector extends DivergenceDetector {
    private final RSIIndicator rsiIndicator;
    private final ClosePriceIndicator closePrice;

    public RSIDivergenceDetector(BarSeries series) {
        super(series);
        this.closePrice = new ClosePriceIndicator(series);
        this.rsiIndicator = new RSIIndicator(closePrice, 14);  // Using 14-period RSI
    }

    @Override
    public List<Divergence> detectDivergences() {
        List<Divergence> divergences = new ArrayList<>();
        List<Double> bullishRSIValues = new ArrayList<>();
        List<Integer> bullishIndexes = new ArrayList<>();
        List<Double> bearishRSIValues = new ArrayList<>();
        List<Integer> bearishIndexes = new ArrayList<>();

        // Start checking from the last 3 candles
        for (int i = Math.max(2, series.getBarCount() - 3); i < series.getBarCount(); i++) {
            // Detect bullish divergence (RSI higher low, price lower low)
            if (isBullishRSICrossover(i)) {
                double currentRSIValue = rsiIndicator.getValue(i).doubleValue();

                // Add this bullish crossover to the list and check for triple divergence
                if (bullishRSIValues.size() < 3) {
                    bullishRSIValues.add(currentRSIValue);
                    bullishIndexes.add(i);  // Store the index
                } else {
                    bullishRSIValues.remove(0); // Remove oldest value to keep the last three
                    bullishIndexes.remove(0);    // Remove the oldest index
                    bullishRSIValues.add(currentRSIValue);
                    bullishIndexes.add(i);       // Add the latest index
                }

                // Check if the latest crossover is the third one and is higher than the previous two
                if (bullishRSIValues.size() == 3 && isTripleBullishDivergence(bullishRSIValues)) {
                    if (isNewLow(i) && isConfirmation(i)) {
                        // Create a Divergence and add all the BarTuples associated with the divergence
                        Divergence divergence = new Divergence(DivergenceType.Triple, IndicatorType.RSI, DivergenceDirection.Bullish);
                        for (Integer index : bullishIndexes) {
                            divergence.addCandle(new BarTuple(index, series.getBar(index)));
                        }
                        divergences.add(divergence);
                    }
                }
            }

            // Detect bearish divergence (RSI lower high, price higher high)
            if (isBearishRSICrossover(i)) {
                double currentRSIValue = rsiIndicator.getValue(i).doubleValue();

                // Add this bearish crossover to the list and check for triple divergence
                if (bearishRSIValues.size() < 3) {
                    bearishRSIValues.add(currentRSIValue);
                    bearishIndexes.add(i);  // Store the index
                } else {
                    bearishRSIValues.remove(0); // Remove oldest value to keep the last three
                    bearishIndexes.remove(0);    // Remove the oldest index
                    bearishRSIValues.add(currentRSIValue);
                    bearishIndexes.add(i);       // Add the latest index
                }

                // Check if the latest crossover is the third one and is lower than the previous two
                if (bearishRSIValues.size() == 3 && isTripleBearishDivergence(bearishRSIValues)) {
                    if (isNewHigh(i) && isConfirmation(i)) {
                        // Create a Divergence and add all the BarTuples associated with the divergence
                        Divergence divergence = new Divergence(DivergenceType.Triple, IndicatorType.RSI, DivergenceDirection.Bearish);
                        for (Integer index : bearishIndexes) {
                            divergence.addCandle(new BarTuple(index, series.getBar(index)));
                        }
                        divergences.add(divergence);
                    }
                }
            }
        }

        return divergences;
    }

    // Check for bullish RSI condition (RSI value increases while price decreases)
    private boolean isBullishRSICrossover(int index) {
        return /*closePrice.getValue(index).isLessThan(closePrice.getValue(index - 1)) &&*/
                rsiIndicator.getValue(index).isGreaterThan(rsiIndicator.getValue(index - 1));
    }

    // Check for bearish RSI condition (RSI value decreases while price increases)
    private boolean isBearishRSICrossover(int index) {
        return /*closePrice.getValue(index).isGreaterThan(closePrice.getValue(index - 1)) &&*/
                rsiIndicator.getValue(index).isLessThan(rsiIndicator.getValue(index - 1));
    }

    // Method to check for continuation in the same direction for confirmation
    private boolean isConfirmation(int index) {
        if (index + 1 >= series.getBarCount()) {
            return false;  // No next value to confirm
        }
        return rsiIndicator.getValue(index + 1).isGreaterThan(rsiIndicator.getValue(index));
    }

    // Check if a new low occurred in the last 10 candles
    private boolean isNewLow(int index) {
        double currentLow = closePrice.getValue(index).doubleValue();
        for (int i = index - 10; i < index; i++) {
            if (i >= 0 && closePrice.getValue(i).doubleValue() <= currentLow) {
                return false;
            }
        }
        return true;
    }

    // Check if a new high occurred in the last 10 candles
    private boolean isNewHigh(int index) {
        double currentHigh = closePrice.getValue(index).doubleValue();
        for (int i = index - 10; i < index; i++) {
            if (i >= 0 && closePrice.getValue(i).doubleValue() >= currentHigh) {
                return false;
            }
        }
        return true;
    }

    // Check if the latest crossover forms a triple bullish divergence
    private boolean isTripleBullishDivergence(List<Double> rsiValues) {
        return rsiValues.get(2) > rsiValues.get(1) && rsiValues.get(1) > rsiValues.get(0);
    }

    // Check if the latest crossover forms a triple bearish divergence
    private boolean isTripleBearishDivergence(List<Double> rsiValues) {
        return rsiValues.get(2) < rsiValues.get(1) && rsiValues.get(1) < rsiValues.get(0);
    }
}
