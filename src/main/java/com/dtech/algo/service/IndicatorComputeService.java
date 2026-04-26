package com.dtech.algo.service;

import com.dtech.algo.series.IntervalBarSeries;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.*;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.adx.MinusDIIndicator;
import org.ta4j.core.indicators.adx.PlusDIIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.num.DecimalNum;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes technical indicators (EMA, Bollinger, MACD, RSI, ADX) on a BarSeries.
 * Stateless, pure computation — no I/O, no browser, no file system.
 */
@Service
public class IndicatorComputeService {

    public TradingViewChartService.ChartData calculateIndicators(IntervalBarSeries series) {
        BarSeries barSeries = series;
        ClosePriceIndicator closePrice = new ClosePriceIndicator(barSeries);

        List<TradingViewChartService.CandleData> candles = new ArrayList<>();
        List<Double> ema50Values = new ArrayList<>();
        List<Double> ema100Values = new ArrayList<>();
        List<Double> ema200Values = new ArrayList<>();
        List<Double> bollingerUpperValues = new ArrayList<>();
        List<Double> bollingerMiddleValues = new ArrayList<>();
        List<Double> bollingerLowerValues = new ArrayList<>();
        List<Double> macdLineValues = new ArrayList<>();
        List<Double> macdSignalValues = new ArrayList<>();
        List<Double> macdHistogramValues = new ArrayList<>();
        List<Double> rsiValues = new ArrayList<>();
        List<Double> adxValues = new ArrayList<>();
        List<Double> plusDIValues = new ArrayList<>();
        List<Double> minusDIValues = new ArrayList<>();

        EMAIndicator ema50 = new EMAIndicator(closePrice, 50);
        EMAIndicator ema100 = new EMAIndicator(closePrice, 100);
        EMAIndicator ema200 = new EMAIndicator(closePrice, 200);

        SMAIndicator sma20 = new SMAIndicator(closePrice, 20);
        StandardDeviationIndicator stdDev20 = new StandardDeviationIndicator(closePrice, 20);
        BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(sma20);
        DecimalNum two = DecimalNum.valueOf(2);
        BollingerBandsUpperIndicator bbUpper = new BollingerBandsUpperIndicator(bbMiddle, stdDev20, two);
        BollingerBandsLowerIndicator bbLower = new BollingerBandsLowerIndicator(bbMiddle, stdDev20, two);

        MACDIndicator macd = new MACDIndicator(closePrice, 12, 26);
        EMAIndicator macdSignal = new EMAIndicator(macd, 9);

        RSIIndicator rsi = new RSIIndicator(closePrice, 14);

        ADXIndicator adx = new ADXIndicator(barSeries, 14);
        PlusDIIndicator plusDI = new PlusDIIndicator(barSeries, 14);
        MinusDIIndicator minusDI = new MinusDIIndicator(barSeries, 14);

        for (int i = 0; i < barSeries.getBarCount(); i++) {
            Bar bar = barSeries.getBar(i);
            long timestamp = bar.getEndTime().getEpochSecond();

            candles.add(new TradingViewChartService.CandleData(
                    timestamp,
                    bar.getOpenPrice().doubleValue(),
                    bar.getHighPrice().doubleValue(),
                    bar.getLowPrice().doubleValue(),
                    bar.getClosePrice().doubleValue(),
                    bar.getVolume().doubleValue()
            ));

            ema50Values.add(ema50.getValue(i).doubleValue());
            ema100Values.add(ema100.getValue(i).doubleValue());
            ema200Values.add(ema200.getValue(i).doubleValue());

            bollingerUpperValues.add(bbUpper.getValue(i).doubleValue());
            bollingerMiddleValues.add(bbMiddle.getValue(i).doubleValue());
            bollingerLowerValues.add(bbLower.getValue(i).doubleValue());

            double macdValue = macd.getValue(i).doubleValue();
            double signalValue = macdSignal.getValue(i).doubleValue();
            macdLineValues.add(macdValue);
            macdSignalValues.add(signalValue);
            macdHistogramValues.add(macdValue - signalValue);

            rsiValues.add(rsi.getValue(i).doubleValue());

            adxValues.add(adx.getValue(i).doubleValue());
            plusDIValues.add(plusDI.getValue(i).doubleValue());
            minusDIValues.add(minusDI.getValue(i).doubleValue());
        }

        return new TradingViewChartService.ChartData(candles, ema50Values, ema100Values, ema200Values,
                bollingerUpperValues, bollingerMiddleValues, bollingerLowerValues,
                macdLineValues, macdSignalValues, macdHistogramValues,
                rsiValues, adxValues, plusDIValues, minusDIValues);
    }

    public String convertDoubleListToJson(List<Double> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) json.append(",");
            Double value = values.get(i);
            if (value != null && !value.isNaN() && !value.isInfinite()) {
                json.append(value);
            } else {
                json.append("null");
            }
        }
        json.append("]");
        return json.toString();
    }
}
