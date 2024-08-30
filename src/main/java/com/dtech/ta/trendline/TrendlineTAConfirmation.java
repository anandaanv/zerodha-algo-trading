package com.dtech.ta.trendline;

import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.adx.MinusDIIndicator;
import org.ta4j.core.indicators.adx.PlusDIIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.num.DecimalNum;

public class TrendlineTAConfirmation {

    // Check Bollinger Bands during retest
    public boolean validateBollingerBands(BarSeries series, int currentIndex, boolean isSupport) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator ema20 = new SMAIndicator(closePrice, 20);

        StandardDeviationIndicator standardDeviation = new StandardDeviationIndicator(closePrice, 20);
        BollingerBandsMiddleIndicator bbmSMA = new BollingerBandsMiddleIndicator(ema20);
        BollingerBandsUpperIndicator upperBand = new BollingerBandsUpperIndicator(bbmSMA, standardDeviation, DecimalNum.valueOf(2));
        BollingerBandsLowerIndicator lowerBand = new BollingerBandsLowerIndicator(bbmSMA, standardDeviation, DecimalNum.valueOf(2));
        
        double currentPrice = series.getBar(currentIndex).getClosePrice().doubleValue();

        if (isSupport) {
            // Support breakout condition
            if (currentPrice < ema20.getValue(currentIndex).doubleValue()) {
                return lowerBand.getValue(currentIndex).doubleValue() > lowerBand.getValue(currentIndex - 1).doubleValue();  // BB expanding from below
            } else {
                return upperBand.getValue(currentIndex).doubleValue() < upperBand.getValue(currentIndex - 1).doubleValue();  // BB shrinking from above
            }
        } else {
            // Resistance breakout condition
            if (currentPrice > ema20.getValue(currentIndex).doubleValue()) {
                return upperBand.getValue(currentIndex).doubleValue() > upperBand.getValue(currentIndex - 1).doubleValue();  // BB expanding from above
            } else {
                return lowerBand.getValue(currentIndex).doubleValue() < lowerBand.getValue(currentIndex - 1).doubleValue();  // BB shrinking from below
            }
        }
    }

    // Check RSI for support and resistance conditions
    public boolean validateRSI(BarSeries series, int currentIndex, boolean isSupport) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        RSIIndicator rsi = new RSIIndicator(closePrice, 14);

        if (isSupport) {
            return rsi.getValue(currentIndex).doubleValue() < 60;  // RSI below 60 for support
        } else {
            double rsiValue = rsi.getValue(currentIndex).doubleValue();
            return rsiValue >= 40 && rsiValue <= 70;  // RSI between 40 and 70 for resistance
        }
    }

    // Validate ADX based on the direction of sell/buy power
    public boolean validateADX(BarSeries series, int currentIndex, boolean isSupport) {
        PlusDIIndicator dmiUp = new PlusDIIndicator(series, 14);
        MinusDIIndicator dmiDown = new MinusDIIndicator(series, 14);
        ADXIndicator adx = new ADXIndicator(series, 14);

        double sellPower = dmiDown.getValue(currentIndex).doubleValue();
        double buyPower = dmiUp.getValue(currentIndex).doubleValue();
        double adxValue = adx.getValue(currentIndex).doubleValue();
        double previousAdxValue = adx.getValue(currentIndex - 1).doubleValue();

        if (isSupport) {
            if (sellPower > buyPower) {
                return adxValue > previousAdxValue;  // ADX rising if sell power is higher
            } else {
                return adxValue < previousAdxValue;  // ADX falling if buy power is higher
            }
        } else {
            if (sellPower < buyPower) {
                return adxValue < previousAdxValue;  // ADX falling if sell power is lower
            } else {
                return adxValue > previousAdxValue;  // ADX rising if sell power is higher
            }
        }
    }

    public boolean validate(BarSeries series, int currentIndex, boolean isSupport) {
        return
                validateADX(series, currentIndex, isSupport) &&
                        validateRSI(series, currentIndex, isSupport) &&
                        validateBollingerBands(series, currentIndex, isSupport);
    }
}
