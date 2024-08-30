package com.dtech.ta.elliott.wave;

import org.ta4j.core.BarSeries;
import org.ta4j.core.num.Num;

public class Wave2Detector extends WaveDetectorBase {

    private int wave1Index;
    private int wave3Index;

    public Wave2Detector(BarSeries series, int wave1Index, int wave3Index) {
        super(series);
        this.wave1Index = wave1Index;
        this.wave3Index = wave3Index;
    }

    @Override
    public int detectWave() {
        Num wave1Price = closePriceIndicator.getValue(wave1Index);
        Num wave3Price = closePriceIndicator.getValue(wave3Index);
        Num fibonacciRetracementLevel = wave3Price.minus(wave3Price.minus(wave1Price).multipliedBy(series.numOf(0.618)));

        for (int i = wave1Index + 1; i < wave3Index; i++) {
            Num currentPrice = closePriceIndicator.getValue(i);
            if (currentPrice.isLessThanOrEqual(fibonacciRetracementLevel)) {
                return i;  // Detected Wave 2
            }
        }
        return wave1Index + 1;  // Default if not found
    }
}
