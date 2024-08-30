package com.dtech.ta.elliott.priceaction;

import com.dtech.ta.BarTuple;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.ta4j.core.BarSeries;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PriceAction {

    private String direction;  // "up" or "down"
    private BarTuple start;
    private BarTuple end;
    private int startIndex;
    private int endIndex;
    private double relevantRSI;  // Highest for uptrend, lowest for downtrend
    private double relevantMACD;  // Highest for uptrend, lowest for downtrend
    private boolean isRetracement;  // True if the movement is corrective, false if impulsive

    public boolean isUpward() {
        return direction.equals("up");
    }

    // Example method to calculate total volume
    public double getTotalVolume(BarSeries series) {
        double totalVolume = 0;
        for (int i = startIndex; i <= endIndex; i++) {
            totalVolume += series.getBar(i).getVolume().doubleValue();
        }
        return totalVolume;
    }

    // Example method to calculate average volume
    public double getAverageVolume(BarSeries series) {
        double totalVolume = getTotalVolume(series);
        return totalVolume / (endIndex - startIndex + 1);
    }

    // Example method to calculate peak volume
    public double getPeakVolume(BarSeries series) {
        double peakVolume = 0;
        for (int i = startIndex; i <= endIndex; i++) {
            double currentVolume = series.getBar(i).getVolume().doubleValue();
            if (currentVolume > peakVolume) {
                peakVolume = currentVolume;
            }
        }
        return peakVolume;
    }
}
