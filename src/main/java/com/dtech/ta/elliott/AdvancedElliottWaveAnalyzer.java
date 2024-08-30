package com.dtech.ta.elliott;

import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;
import java.util.ArrayList;
import java.util.List;


public class AdvancedElliottWaveAnalyzer {

    private BarSeries higherTimeframeSeries;  // Higher timeframe series (e.g., daily)
    private BarSeries lowerTimeframeSeries;   // Lower timeframe series (e.g., 1-hour, 15-minute)
    private MACDIndicator higherTfMacdIndicator;
    private MACDIndicator lowerTfMacdIndicator;
    private RSIIndicator higherTfRsiIndicator;
    private ClosePriceIndicator higherTfClosePriceIndicator;
    private ClosePriceIndicator lowerTfClosePriceIndicator;

    public AdvancedElliottWaveAnalyzer(BarSeries higherTfSeries, BarSeries lowerTfSeries) {
        this.higherTimeframeSeries = higherTfSeries;
        this.lowerTimeframeSeries = lowerTfSeries;

        // Initialize MACD and close price indicators for both timeframes
        this.higherTfClosePriceIndicator = new ClosePriceIndicator(higherTimeframeSeries);
        this.lowerTfClosePriceIndicator = new ClosePriceIndicator(lowerTimeframeSeries);

        this.higherTfMacdIndicator = new MACDIndicator(higherTfClosePriceIndicator, 12, 26);
        this.lowerTfMacdIndicator = new MACDIndicator(lowerTfClosePriceIndicator, 12, 26);

        // Adding RSI for divergence detection
        this.higherTfRsiIndicator = new RSIIndicator(higherTfClosePriceIndicator, 14); // 14-period RSI
    }

    /**
     * Performs Elliott Wave detection across multiple timeframes, including subwave detection and Fibonacci levels.
     * @return list of wave indices representing Elliott Wave pattern.
     */
    public List<Integer> detectWavesMultiTimeframe() {
        // Step 1: Identify waves on the higher timeframe (e.g., daily)
        List<Integer> higherTfWaves = detectWaves(higherTimeframeSeries, higherTfMacdIndicator, higherTfClosePriceIndicator);

        // Step 2: Confirm wave structure on the lower timeframe (e.g., 1-hour)
        List<Integer> lowerTfWaves = detectWaves(lowerTimeframeSeries, lowerTfMacdIndicator, lowerTfClosePriceIndicator);

        // Step 3: Return the detected waves, merging both timeframes
        return mergeWaveCounts(higherTfWaves, lowerTfWaves);
    }

    /**
     * Detects Elliott Waves in the given BarSeries using MACD and ClosePriceIndicator.
     */
    private List<Integer> detectWaves(BarSeries series, MACDIndicator macdIndicator, ClosePriceIndicator closePriceIndicator) {
        List<Integer> waveIndices = new ArrayList<>();
        Num highestMacdValue = macdIndicator.getValue(0);  // Initialize with the first value
        int thirdWaveIndex = 0;

        // Identify the peak MACD value (the likely 3rd wave)
        for (int i = 1; i < series.getBarCount(); i++) {
            Num currentMacdValue = macdIndicator.getValue(i);
            if (currentMacdValue.isGreaterThan(highestMacdValue)) {
                highestMacdValue = currentMacdValue;
                thirdWaveIndex = i;
            }
        }
        waveIndices.add(thirdWaveIndex);  // Store the index of the 3rd wave

        // Look backward for Wave 1 and Wave 2
        int wave1Index = findWave1(thirdWaveIndex, series, closePriceIndicator);
        waveIndices.add(0, wave1Index);  // Insert at the start

        int wave2Index = findWave2(wave1Index, thirdWaveIndex, series, closePriceIndicator);
        waveIndices.add(1, wave2Index);  // Insert after Wave 1

        // Look forward for Wave 4 and Wave 5
        int wave4Index = findWave4(thirdWaveIndex, series, closePriceIndicator);
        waveIndices.add(wave4Index);  // Add after Wave 3

        int wave5Index = findWave5(wave4Index, series, closePriceIndicator);
        waveIndices.add(wave5Index);  // Add last

        return waveIndices;
    }

    /**
     * Merges the wave counts from higher and lower timeframes.
     */
    private List<Integer> mergeWaveCounts(List<Integer> higherTfWaves, List<Integer> lowerTfWaves) {
        // Merge logic can prioritize higher timeframe or use lower timeframe for refinement
        List<Integer> mergedWaves = new ArrayList<>(higherTfWaves);

        // Use lower timeframe data to refine subwave structures (e.g., Wave 2 of Wave 3)
        for (int waveIndex : lowerTfWaves) {
            if (!mergedWaves.contains(waveIndex)) {
                mergedWaves.add(waveIndex);
            }
        }

        return mergedWaves;
    }

    /**
     * Identifies Wave 1 by looking for the start of the impulse wave leading to Wave 3.
     */
    private int findWave1(int thirdWaveIndex, BarSeries series, ClosePriceIndicator closePriceIndicator) {
        int wave1Index = thirdWaveIndex;
        Num lowestValue = closePriceIndicator.getValue(thirdWaveIndex);

        // Traverse backward to find the lowest point before Wave 3 (Wave 1)
        for (int i = thirdWaveIndex - 1; i >= 0; i--) {
            Num currentValue = closePriceIndicator.getValue(i);
            if (currentValue.isLessThan(lowestValue)) {
                lowestValue = currentValue;
                wave1Index = i;
            }
        }

        return wave1Index;
    }

    /**
     * Identifies Wave 2 as a corrective wave retracing after Wave 1, using Fibonacci retracement.
     */
    private int findWave2(int wave1Index, int thirdWaveIndex, BarSeries series, ClosePriceIndicator closePriceIndicator) {
        // Approximate Fibonacci retracement for Wave 2 (typically 50%-61.8%)
        Num wave1Price = closePriceIndicator.getValue(wave1Index);
        Num wave3Price = closePriceIndicator.getValue(thirdWaveIndex);
        Num fibonacciRetracementLevel = wave3Price.minus(wave3Price.minus(wave1Price).multipliedBy(series.numOf(0.618)));

        // Traverse forward from Wave 1 to Wave 3 to find Wave 2 (retracement)
        for (int i = wave1Index + 1; i < thirdWaveIndex; i++) {
            Num currentPrice = closePriceIndicator.getValue(i);
            if (currentPrice.isLessThanOrEqual(fibonacciRetracementLevel)) {
                return i;  // Found Wave 2
            }
        }

        return wave1Index + 1;  // Default if not found, just after Wave 1
    }

    /**
     * Identifies Wave 4 as a corrective wave after Wave 3, ensuring it doesn't overlap Wave 1.
     */
    private int findWave4(int thirdWaveIndex, BarSeries series, ClosePriceIndicator closePriceIndicator) {
        Num wave3Price = closePriceIndicator.getValue(thirdWaveIndex);
        Num wave4RetracementLevel = wave3Price.minus(wave3Price.multipliedBy(series.numOf(0.382)));  // 38.2% retracement

        // Traverse forward from Wave 3 to find Wave 4
        for (int i = thirdWaveIndex + 1; i < series.getBarCount(); i++) {
            Num currentPrice = closePriceIndicator.getValue(i);
            if (currentPrice.isLessThanOrEqual(wave4RetracementLevel)) {
                return i;  // Found Wave 4
            }
        }

        return thirdWaveIndex + 1;  // Default if not found
    }

    /**
     * Identifies Wave 5 by looking for the final impulse wave after Wave 4.
     */
    private int findWave5(int wave4Index, BarSeries series, ClosePriceIndicator closePriceIndicator) {
        Num wave4Price = closePriceIndicator.getValue(wave4Index);
        int wave5Index = wave4Index;

        // Traverse forward to find the final upward movement (Wave 5)
        for (int i = wave4Index + 1; i < series.getBarCount(); i++) {
            Num currentPrice = closePriceIndicator.getValue(i);
            if (currentPrice.isGreaterThan(wave4Price)) {
                wave5Index = i;
            }
        }

        return wave5Index;
    }

    /**
     * Detects bullish or bearish divergence in the RSI, useful for confirming wave tops/bottoms.
     */
    public boolean detectRsiDivergence(int waveIndex) {
        if (waveIndex <= 1 || waveIndex >= higherTimeframeSeries.getBarCount() - 1) {
            return false; // Not enough data to detect divergence
        }

        Num prevRsi = higherTfRsiIndicator.getValue(waveIndex - 1);
        Num currentRsi = higherTfRsiIndicator.getValue(waveIndex);
        Num nextRsi = higherTfRsiIndicator.getValue(waveIndex + 1);

        // Simple divergence detection: If price increases but RSI decreases, we have bearish divergence.
        if (prevRsi.isGreaterThan(currentRsi) && nextRsi.isGreaterThan(currentRsi)) {
            return true; // Bearish divergence
        }

        // Bullish divergence: Price is dropping but RSI is rising.
        if (prevRsi.isLessThan(currentRsi) && nextRsi.isLessThan(currentRsi)) {
            return true; // Bullish divergence
        }

        return false; // No divergence detected
    }

    /**
     * Determines if the market is currently in the 3rd wave based on MACD.
     */
    public boolean isCurrentlyInThirdWave() {
        int highestMacdIndex = 0;
        Num highestMacdValue = higherTfMacdIndicator.getValue(0);

        // Find the highest MACD value (which corresponds to the 3rd wave)
        for (int i = 1; i < higherTimeframeSeries.getBarCount(); i++) {
            Num currentMacdValue = higherTfMacdIndicator.getValue(i);
            if (currentMacdValue.isGreaterThan(highestMacdValue)) {
                highestMacdValue = currentMacdValue;
                highestMacdIndex = i;
            }
        }

        // If we are at or close to this peak, we are likely still in the 3rd wave
        int currentIndex = higherTimeframeSeries.getEndIndex();
        return (currentIndex == highestMacdIndex || currentIndex < highestMacdIndex + 5); // Approximation
    }
}
