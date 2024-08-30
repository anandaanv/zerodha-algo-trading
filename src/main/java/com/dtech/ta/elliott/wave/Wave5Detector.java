package com.dtech.ta.elliott.wave;

import org.ta4j.core.BarSeries;
import org.ta4j.core.num.Num;

public class Wave5Detector extends WaveDetectorBase {

    private int wave4Index;

    public Wave5Detector(BarSeries series, int wave4Index) {
        super(series);
        this.wave4Index = wave4Index;
    }

    @Override
    public int detectWave() {
        Num wave4Price = closePriceIndicator.getValue(wave4Index);
        int wave5Index = wave4Index;

        for (int i = wave4Index + 1; i < series.getBarCount(); i++) {
            Num currentPrice = closePriceIndicator.getValue(i);
            if (currentPrice.isGreaterThan(wave4Price)) {
                wave5Index = i;
            }
        }
        return wave5Index;
    }
}
