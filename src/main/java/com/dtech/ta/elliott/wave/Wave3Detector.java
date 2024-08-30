package com.dtech.ta.elliott.wave;

import org.ta4j.core.BarSeries;
import org.ta4j.core.num.Num;

public class Wave3Detector extends WaveDetectorBase {

    public Wave3Detector(BarSeries series) {
        super(series);
    }

    @Override
    public int detectWave() {
        int wave3Index = 0;
        Num highestMacdValue = macdIndicator.getValue(0);

        for (int i = 1; i < series.getBarCount(); i++) {
            Num currentMacdValue = macdIndicator.getValue(i);
            if (currentMacdValue.isGreaterThan(highestMacdValue)) {
                highestMacdValue = currentMacdValue;
                wave3Index = i;
            }
        }
        return wave3Index;
    }
}
