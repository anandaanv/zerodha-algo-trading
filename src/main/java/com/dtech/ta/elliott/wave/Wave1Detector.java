package com.dtech.ta.elliott.wave;

import com.dtech.ta.elliott.priceaction.PriceAction;
import org.ta4j.core.BarSeries;
import org.ta4j.core.num.Num;

import java.util.ArrayList;
import java.util.List;

public class Wave1Detector {

    private final BarSeries series;
    private final List<PriceAction> priceActions;

    public Wave1Detector(BarSeries series, List<PriceAction> priceActions) {
        this.series = series;
        this.priceActions = priceActions;
    }

    public List<PriceAction> detectPotentialWave1s() {
        List<PriceAction> potentialWave1s = new ArrayList<>();

        for (PriceAction pa : priceActions) {
            // Check for an upward price action as a candidate for Wave 1
            if (pa.isUpward()) {
                // Check MACD and RSI for bullish divergence or strong momentum
                double macdValue = pa.getRelevantMACD();
                double rsiValue = pa.getRelevantRSI();

                if (isBullishDivergenceOrMomentum(macdValue, rsiValue)) {
                    // Check volume (if it's significant compared to the previous PriceActions)
                    if (isVolumeIncreasing(pa)) {
                        // Add to the list of potential Wave 1s
                        potentialWave1s.add(pa);
                    }
                }
            }
        }

        return potentialWave1s;
    }

    private boolean isBullishDivergenceOrMomentum(double macdValue, double rsiValue) {
        // Basic checks for MACD and RSI values, can be adjusted for more complex logic
        return macdValue > 0 && rsiValue > 30;
    }

    private boolean isVolumeIncreasing(PriceAction pa) {
        // Check if the volume is increasing compared to previous actions
        // This is a simplified example, and could be more complex depending on your data structure
        return pa.getAverageVolume(series) > getPreviousVolume(pa);
    }

    private double getPreviousVolume(PriceAction pa) {
        int index = priceActions.indexOf(pa);
        if (index > 0) {
            PriceAction previousAction = priceActions.get(index - 1);
            return previousAction.getAverageVolume(series);  // Use average volume of the previous PriceAction
        }
        return 0;
    }

}
