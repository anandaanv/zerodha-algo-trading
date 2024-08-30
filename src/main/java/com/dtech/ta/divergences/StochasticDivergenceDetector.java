package com.dtech.ta.divergences;

import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.StochasticOscillatorKIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import com.dtech.ta.BarTuple;

import java.util.ArrayList;
import java.util.List;

public class StochasticDivergenceDetector extends DivergenceDetector {
    private final StochasticOscillatorKIndicator stochasticIndicator;
    private final ClosePriceIndicator closePrice;

    public StochasticDivergenceDetector(BarSeries series) {
        super(series);
        this.closePrice = new ClosePriceIndicator(series);
        this.stochasticIndicator = new StochasticOscillatorKIndicator(series, 14); // Using 14-period Stochastic
    }

    @Override
    public List<Divergence> detectDivergences() {
        List<Divergence> divergences = new ArrayList<>();
        List<Double> bullishStochasticValues = new ArrayList<>();
        List<Integer> bullishIndexes = new ArrayList<>();
        List<Double> bearishStochasticValues = new ArrayList<>();
        List<Integer> bearishIndexes = new ArrayList<>();

        // Start checking from the last 3 candles
        for (int i = Math.max(2, series.getBarCount() - 3); i < series.getBarCount(); i++) {
            // Detect bullish divergence (Stochastic higher low, price lower low)
            if (isBullishStochasticCrossover(i)) {
                double currentStochasticValue = stochasticIndicator.getValue(i).doubleValue();

                // Add this bullish crossover to the list and check for triple divergence
                if (bullishStochasticValues.size() < 3) {
                    bullishStochasticValues.add(currentStochasticValue);
                    bullishIndexes.add(i);  // Store the index
                } else {
                    bullishStochasticValues.remove(0); // Remove oldest value to keep the last three
                    bullishIndexes.remove(0);    // Remove the oldest index
                    bullishStochasticValues.add(currentStochasticValue);
                    bullishIndexes.add(i);       // Add the latest index
                }

                // Check if the latest crossover is the third one and is higher than the previous two
                if (bullishStochasticValues.size() == 3 && isTripleBullishDivergence(bullishStochasticValues)) {
                    if (isNewLow(i) && isConfirmation(i)) {
                        // Create a Divergence and add all the BarTuples associated with the divergence
                        Divergence divergence = new Divergence(DivergenceType.Triple, IndicatorType.STOCH, DivergenceDirection.Bullish);
                        for (Integer index : bullishIndexes) {
                            divergence.addCandle(new BarTuple(index, series.getBar(index)));
                        }
                        divergences.add(divergence);
                    }
                }
            }

            // Detect bearish divergence (Stochastic lower high, price higher high)
            if (isBearishStochasticCrossover(i)) {
                double currentStochasticValue = stochasticIndicator.getValue(i).doubleValue();

                // Add this bearish crossover to the list and check for triple divergence
                if (bearishStochasticValues.size() < 3) {
                    bearishStochasticValues.add(currentStochasticValue);
                    bearishIndexes.add(i);  // Store the index
                } else {
                    bearishStochasticValues.remove(0); // Remove oldest value to keep the last three
                    bearishIndexes.remove(0);    // Remove the oldest index
                    bearishStochasticValues.add(currentStochasticValue);
                    bearishIndexes.add(i);       // Add the latest index
                }

                // Check if the latest crossover is the third one and is lower than the previous two
                if (bearishStochasticValues.size() == 3 && isTripleBearishDivergence(bearishStochasticValues)) {
                    if (isNewHigh(i) && isConfirmation(i)) {
                        // Create a Divergence and add all the BarTuples associated with the divergence
                        Divergence divergence = new Divergence(DivergenceType.Triple, IndicatorType.STOCH, DivergenceDirection.Bearish);
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

    // Check for bullish stochastic condition (Stochastic value increases while price decreases)
    private boolean isBullishStochasticCrossover(int index) {
        return closePrice.getValue(index).isLessThan(closePrice.getValue(index - 1)) &&
                stochasticIndicator.getValue(index).isGreaterThan(stochasticIndicator.getValue(index - 1));
    }

    // Check for bearish stochastic condition (Stochastic value decreases while price increases)
    private boolean isBearishStochasticCrossover(int index) {
        return closePrice.getValue(index).isGreaterThan(closePrice.getValue(index - 1)) &&
                stochasticIndicator.getValue(index).isLessThan(stochasticIndicator.getValue(index - 1));
    }

    // Method to check for continuation in the same direction for confirmation
    private boolean isConfirmation(int index) {
        if (index + 1 >= series.getBarCount()) {
            return false;  // No next value to confirm
        }
        return stochasticIndicator.getValue(index + 1).isGreaterThan(stochasticIndicator.getValue(index));
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
    private boolean isTripleBullishDivergence(List<Double> stochasticValues) {
        return stochasticValues.get(2) > stochasticValues.get(1) && stochasticValues.get(1) > stochasticValues.get(0);
    }

    // Check if the latest crossover forms a triple bearish divergence
    private boolean isTripleBearishDivergence(List<Double> stochasticValues) {
        return stochasticValues.get(2) < stochasticValues.get(1) && stochasticValues.get(1) < stochasticValues.get(0);
    }
}
