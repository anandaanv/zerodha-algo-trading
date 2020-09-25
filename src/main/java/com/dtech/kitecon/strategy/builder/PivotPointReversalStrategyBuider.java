package com.dtech.kitecon.strategy.builder;

import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.strategy.TradeDirection;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Rule;
import org.ta4j.core.Strategy;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.HighestValueIndicator;
import org.ta4j.core.indicators.helpers.LowestValueIndicator;
import org.ta4j.core.indicators.helpers.OpenPriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.indicators.pivotpoints.PivotLevel;
import org.ta4j.core.indicators.pivotpoints.PivotPointIndicator;
import org.ta4j.core.indicators.pivotpoints.StandardReversalIndicator;
import org.ta4j.core.indicators.pivotpoints.TimeLevel;
import org.ta4j.core.num.PrecisionNum;
import org.ta4j.core.trading.rules.OverIndicatorRule;
import org.ta4j.core.trading.rules.StopGainRule;
import org.ta4j.core.trading.rules.StopLossRule;
import org.ta4j.core.trading.rules.TrailingStopLossRule;
import org.ta4j.core.trading.rules.UnderIndicatorRule;

@Component
public class PivotPointReversalStrategyBuider extends BaseStrategyBuilder {


  private static Strategy create3DaySmaUnderStrategy(BarSeries series) {
    ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
    OpenPriceIndicator openPriceIndicator1 = new OpenPriceIndicator(series);
    LowestValueIndicator lowestPrice = new LowestValueIndicator(new ClosePriceIndicator(series),
        10);
    PivotPointIndicator pivotPoints = new PivotPointIndicator(series, TimeLevel.BARBASED);
    StandardReversalIndicator reversalIndicator = new StandardReversalIndicator(pivotPoints,
        PivotLevel.SUPPORT_1);
    SMAIndicator tenSMA = new SMAIndicator(closePrice, 10);
    SMAIndicator fiftySMA1 = new SMAIndicator(closePrice, 50);
    SMAIndicator twoHundredSMA = new SMAIndicator(closePrice, 200);
    MACDIndicator macdIndicator = new MACDIndicator(closePrice);

    VolumeIndicator volumeIndicator = new VolumeIndicator(series);
    SMAIndicator voluemSma10 = new SMAIndicator(volumeIndicator, 10);
    SMAIndicator voluemSma50 = new SMAIndicator(volumeIndicator, 50);

    Rule entryRule = new OverIndicatorRule(reversalIndicator, lowestPrice)
        .and(new OverIndicatorRule(macdIndicator, 0.01))
        .and(new OverIndicatorRule(openPriceIndicator1, reversalIndicator))
        .and(new OverIndicatorRule(openPriceIndicator1, tenSMA))
        .and(new OverIndicatorRule(voluemSma10, voluemSma50))
        .and(new UnderIndicatorRule(new RSIIndicator(closePrice, 14), 55))
        .and(new OverIndicatorRule(new RSIIndicator(closePrice, 14), 30))
        .and(new UnderIndicatorRule(closePrice, fiftySMA1));

    Rule exitRule = new StopGainRule(closePrice, 5)
        .or(new StopLossRule(closePrice, 2))
        .or(new TrailingStopLossRule(closePrice, PrecisionNum.valueOf(1)));

    return new BaseStrategy(
        entryRule, exitRule
    );
  }

  private static Strategy create3DaySmaUnderSellStrategy(BarSeries series) {
    ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
    OpenPriceIndicator openPriceIndicator1 = new OpenPriceIndicator(series);
    HighestValueIndicator highestPrice = new HighestValueIndicator(new ClosePriceIndicator(series),
        10);
    PivotPointIndicator pivotPoints = new PivotPointIndicator(series, TimeLevel.BARBASED);
    StandardReversalIndicator reversalIndicator = new StandardReversalIndicator(pivotPoints,
        PivotLevel.RESISTANCE_1);
    MACDIndicator macdIndicator = new MACDIndicator(closePrice);
    SMAIndicator tenSMA = new SMAIndicator(closePrice, 10);
    SMAIndicator fiftySMA1 = new SMAIndicator(closePrice, 50);

    Rule entryRule = new UnderIndicatorRule(reversalIndicator, highestPrice)
        .and(new UnderIndicatorRule(closePrice, tenSMA))
        .and(new UnderIndicatorRule(macdIndicator, tenSMA))
        .and(new UnderIndicatorRule(openPriceIndicator1, reversalIndicator))
        .and(new OverIndicatorRule(closePrice, fiftySMA1));

    Rule exitRule = new StopGainRule(closePrice, -2.5)
        .or(new StopLossRule(closePrice, -0.7))
        .or(new TrailingStopLossRule(closePrice, PrecisionNum.valueOf(1)));

    return new BaseStrategy(entryRule, exitRule);
  }

  @Override
  public TradeDirection getTradeDirection() {
    return TradeDirection.Both;
  }

  @Override
  protected Strategy getSellStrategy(Instrument tradingIdentity,
      Map<Instrument, BarSeries> barSeriesMap,
      Map<String, String> sell) {
    return create3DaySmaUnderStrategy(barSeriesMap.get(tradingIdentity));
  }

  @Override
  protected Strategy getBuyStrategy(Instrument tradingIdentity,
      Map<Instrument, BarSeries> barSeriesMap,
      Map<String, String> config) {
    return create3DaySmaUnderSellStrategy(barSeriesMap.get(tradingIdentity));
  }

  @Override
  public String getName() {
    return "PivotPointReversal";
  }
}
