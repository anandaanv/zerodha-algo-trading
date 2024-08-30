package com.dtech.ta.elliott.wave;

import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

public abstract class WaveDetectorBase {
    protected BarSeries series;
    protected MACDIndicator macdIndicator;
    protected ClosePriceIndicator closePriceIndicator;

    public WaveDetectorBase(BarSeries series) {
        this.series = series;
        this.closePriceIndicator = new ClosePriceIndicator(series);
        this.macdIndicator = new MACDIndicator(closePriceIndicator, 12, 26);
    }

    public abstract int detectWave();
}
