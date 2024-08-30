package com.dtech.ta.elliott.wave;

import org.ta4j.core.BarSeries;
import org.ta4j.core.num.Num;

public class Wave4Detector extends WaveDetectorBase {

    private int wave3Index;

    public Wave4Detector(BarSeries series, int wave3Index) {
        super(series);
        this.wave3Index = wave3Index;
    }

    @Override
    public int detectWave() {
        Num wave3Price = closePriceIndicator.getValue(wave3Index);
        Num wave4RetracementLevel = wave3Price.minus(wave3Price.multipliedBy(series.numOf(0.382)));  // 38.2% retracement

        for (int i = wave3Index + 1; i < series.getBarCount(); i++) {
            Num currentPrice = closePriceIndicator.getValue(i);
            if (currentPrice.isLessThanOrEqual(wave4RetracementLevel)) {
                return i;  // Detected Wave 4
            }
        }
        return wave3Index + 1;  // Default if not found
    }
}
