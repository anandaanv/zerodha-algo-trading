package com.dtech.ta.divergences;

import com.dtech.ta.BarTuple;
import com.dtech.ta.trendline.ActiveTrendlineAnalysis;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.util.ArrayList;
import java.util.List;

public class MACDDivergenceDetector extends DivergenceDetector {

    private final MACDIndicator macdIndicator;
    private final EMAIndicator signalLine;
    private final ClosePriceIndicator closePrice;
    private final ActiveTrendlineAnalysis trendlineAnalysis;

    public MACDDivergenceDetector(BarSeries series, ActiveTrendlineAnalysis trendlineAnalysis) {
        super(series);
        this.closePrice = new ClosePriceIndicator(series);
        this.macdIndicator = new MACDIndicator(closePrice, 12, 26);
        this.signalLine = new EMAIndicator(macdIndicator, 9);  // Signal line with 9-period EMA
        this.trendlineAnalysis = trendlineAnalysis;
    }

    @Override
    public List<Divergence> detectDivergences() {
        List<Divergence> divergences = new ArrayList<>();

        // Detect crossovers and find previous similar crossovers
        for (int i = series.getBarCount() -50;  i < series.getBarCount(); i++) {
            // Bullish crossover (MACD crosses above the signal line)
            Divergence divergence = validateDivergenceAtIndex(i);
            if (divergence  != null) {
                Divergence previousDivergence = validateDivergenceAtIndex(divergence.getCandles().get(1).getIndex());
                if (previousDivergence  != null && divergence.getType() == previousDivergence.getType()) {
                    previousDivergence.addCandle(divergence.getCandles().getFirst());
                }
                divergences.add(divergence);
            }
        }

        return divergences;
    }


    public Divergence validateDivergenceAtIndex(int i) {
        // Detect if there's a bullish or bearish crossover at index i
        if (isBullishCrossover(i)) {
            Integer previousBullishCrossoverIndex = findPreviousBullishCrossover(i);
            if (previousBullishCrossoverIndex != null) {
                BarTuple currentPriceLow = findNearbyLow(i);
                BarTuple previousPriceLow = findNearbyLow(previousBullishCrossoverIndex);
                double currentMACDLow = findNearbyMACDLow(i);
                double previousMACDLow = findNearbyMACDLow(previousBullishCrossoverIndex);

                // Compare the lows and return a valid divergence if found
                if (currentPriceLow != null && previousPriceLow != null && currentMACDLow != -1 && previousMACDLow != -1) {
                    if (compareBullishDivergence(previousPriceLow, currentPriceLow, previousMACDLow, currentMACDLow)) {
                        Divergence divergence = new Divergence(DivergenceType.Double, IndicatorType.MACD, DivergenceDirection.Bullish);
                        divergence.addCandle(currentPriceLow);
                        divergence.addCandle(previousPriceLow);
                        return divergence;
                    }
                }
            }
        }

        if (isBearishCrossover(i)) {
            Integer previousBearishCrossoverIndex = findPreviousBearishCrossover(i);
            if (previousBearishCrossoverIndex != null) {
                BarTuple currentPriceHigh = findNearbyHigh(i);
                BarTuple previousPriceHigh = findNearbyHigh(previousBearishCrossoverIndex);
                double currentMACDHigh = findNearbyMACDHigh(i);
                double previousMACDHigh = findNearbyMACDHigh(previousBearishCrossoverIndex);

                // Compare the highs and return a valid divergence if found
                if (currentPriceHigh != null && previousPriceHigh != null && currentMACDHigh != -1 && previousMACDHigh != -1) {
                    if (compareBearishDivergence(previousPriceHigh, currentPriceHigh, previousMACDHigh, currentMACDHigh)) {
                        Divergence divergence = new Divergence(DivergenceType.Double, IndicatorType.MACD, DivergenceDirection.Bearish);
                        divergence.addCandle(currentPriceHigh);
                        divergence.addCandle(previousPriceHigh);
                        return divergence;
                    }
                }
            }
        }

        return null;  // Return null if no divergence is found
    }


    private Integer findPreviousBullishCrossover(int currentIndex) {
        for (int i = currentIndex - 1; i >= 2; i--) {
            // Check if a bullish crossover occurred at index i
            if (isBullishCrossover(i)) {
                return i;  // Return the index of the previous bullish crossover
            }
        }
        return null;  // Return null if no previous bullish crossover is found
    }

    private Integer findPreviousBearishCrossover(int currentIndex) {
        for (int i = currentIndex - 1; i >= 2; i--) {
            // Check if a bearish crossover occurred at index i
            if (isBearishCrossover(i)) {
                return i;  // Return the index of the previous bearish crossover
            }
        }
        return null;  // Return null if no previous bearish crossover is found
    }


    // Detect if there's a bullish MACD crossover
    private boolean isBullishCrossover(int index) {
        return macdIndicator.getValue(index).isGreaterThan(signalLine.getValue(index)) &&
                macdIndicator.getValue(index - 1).isLessThan(signalLine.getValue(index - 1)) &&
                macdIndicator.getValue(index).doubleValue() < 0;
    }

    // Detect if there's a bearish MACD crossover
    private boolean isBearishCrossover(int index) {
        return macdIndicator.getValue(index).isLessThan(signalLine.getValue(index)) &&
                macdIndicator.getValue(index - 1).isGreaterThan(signalLine.getValue(index - 1)) &&
                macdIndicator.getValue(index).doubleValue() > 0;

    }

    // Find the nearest price low near the crossover
    private BarTuple findNearbyLow(int index) {
        List<BarTuple> lows = trendlineAnalysis.getCombinedHighLows(series, true);  // true for lows
        return findClosestTuple(lows, index);
    }

    // Find the nearest price high near the crossover
    private BarTuple findNearbyHigh(int index) {
        List<BarTuple> highs = trendlineAnalysis.getCombinedHighLows(series, false);  // false for highs
        return findClosestTuple(highs, index);
    }

    // Find the nearest MACD low near the crossover
    private double findNearbyMACDLow(int index) {
        double macdLow = Double.MAX_VALUE;
        for (int i = Math.max(0, index - 10); i <= index; i++) {  // Search in a 10-candle window
            macdLow = Math.min(macdLow, macdIndicator.getValue(i).doubleValue());
        }
        return macdLow == Double.MAX_VALUE ? -1 : macdLow;
    }

    // Find the nearest MACD high near the crossover
    private double findNearbyMACDHigh(int index) {
        double macdHigh = Double.MIN_VALUE;
        for (int i = Math.max(0, index - 10); i <= index; i++) {  // Search in a 10-candle window
            macdHigh = Math.max(macdHigh, macdIndicator.getValue(i).doubleValue());
        }
        return macdHigh == Double.MIN_VALUE ? -1 : macdHigh;
    }

    // Compare the MACD and price lows between two crossovers for bullish divergence
    private boolean compareBullishDivergence(BarTuple previousPriceLow, BarTuple currentPriceLow, double previousMACDLow, double currentMACDLow) {
        return currentPriceLow.getValue() < previousPriceLow.getValue() &&  // Price is making a lower low
                currentMACDLow > previousMACDLow;  // MACD is making a higher low
    }

    // Compare the MACD and price highs between two crossovers for bearish divergence
    private boolean compareBearishDivergence(BarTuple previousPriceHigh, BarTuple currentPriceHigh, double previousMACDHigh, double currentMACDHigh) {
        return currentPriceHigh.getValue() > previousPriceHigh.getValue() &&  // Price is making a higher high
                currentMACDHigh < previousMACDHigh;  // MACD is making a lower high
    }

    // Utility function to find the closest high/low near the crossover
    private BarTuple findClosestTuple(List<BarTuple> tuples, int crossoverIndex) {
        BarTuple closestTuple = null;
        for (BarTuple tuple : tuples) {
            if (Math.abs(tuple.getIndex() - crossoverIndex) <= 10) {  // Check within a 10-candle window
                closestTuple = tuple;
                break;
            }
        }
        return closestTuple;
    }
}
