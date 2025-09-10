package com.dtech.ta.patterns;

import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.StochasticOscillatorKIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

public class IndicatorCalculator {

    private final BarSeries series;
    private final int emaPeriod;
    private RSIIndicator rsi;
    private StochasticOscillatorKIndicator stochastic;
    private ADXIndicator adx;
    private EMAIndicator rsiEma;
    private EMAIndicator stochasticEma;
    private EMAIndicator adxEma;

    public IndicatorCalculator(BarSeries series, int emaPeriod) {
        this.series = series;
        this.emaPeriod = emaPeriod;
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
                rsi = new RSIIndicator(closePrice, 14);
        stochastic = new StochasticOscillatorKIndicator(series, 14);
        adx = new ADXIndicator(series, 14);

        rsiEma = new EMAIndicator(rsi, emaPeriod);
        stochasticEma = new EMAIndicator(stochastic, emaPeriod);
        adxEma = new EMAIndicator(adx, emaPeriod);
    }

    public double getIndicatorAverage(int index) {
        return (rsiEma.getValue(index).doubleValue() + stochasticEma.getValue(index).doubleValue() + adxEma.getValue(index).doubleValue()) / 3.0;
    }
}
