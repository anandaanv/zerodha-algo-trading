package com.dtech.kitecon.strategy.builder;

import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.strategy.TradeDirection;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Rule;
import org.ta4j.core.Strategy;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.candles.BearishEngulfingIndicator;
import org.ta4j.core.indicators.candles.BearishHaramiIndicator;
import org.ta4j.core.indicators.candles.BullishEngulfingIndicator;
import org.ta4j.core.indicators.candles.BullishHaramiIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.ConstantIndicator;
import org.ta4j.core.indicators.helpers.DifferenceIndicator;
import org.ta4j.core.indicators.helpers.DifferencePercentage;
import org.ta4j.core.indicators.helpers.DifferenceRatioIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.indicators.helpers.OpenPriceIndicator;
import org.ta4j.core.indicators.pivotpoints.TimeLevel;
import org.ta4j.core.indicators.range.LastCandleOfPeriod;
import org.ta4j.core.indicators.range.OpeningRangeHigh;
import org.ta4j.core.indicators.range.OpeningRangeLow;
import org.ta4j.core.indicators.range.PercentageIndicator;
import org.ta4j.core.indicators.range.TimeOfCandle;
import org.ta4j.core.num.DoubleNum;
import org.ta4j.core.num.Num;
import org.ta4j.core.num.PrecisionNum;
import org.ta4j.core.trading.rules.BooleanIndicatorRule;
import org.ta4j.core.trading.rules.BooleanRule;
import org.ta4j.core.trading.rules.CrossedDownIndicatorRule;
import org.ta4j.core.trading.rules.CrossedUpIndicatorRule;
import org.ta4j.core.trading.rules.IsEqualRule;
import org.ta4j.core.trading.rules.NotRule;
import org.ta4j.core.trading.rules.OverIndicatorRule;
import org.ta4j.core.trading.rules.StopGainRule;
import org.ta4j.core.trading.rules.TrailingStopLossRule;
import org.ta4j.core.trading.rules.UnderIndicatorRule;
import org.ta4j.core.trading.rules.WithinPercentageRule;
import org.ta4j.core.trading.rules.WithinRangeRule;

@Component
public class OpeningRangeBreakoutStrategyBuider extends BaseStrategyBuilder {

  private static int orbLimit = 2;
  private static double falseBreakoutOffset = 0.01;

  private static Strategy createOpeningPriceBreakoutBullish(BarSeries series) {
    OpeningRangeHigh high = new OpeningRangeHigh(series, TimeLevel.DAY, orbLimit);
    PercentageIndicator entry = new PercentageIndicator(high, PrecisionNum.valueOf(falseBreakoutOffset));
    OpeningRangeLow low = new OpeningRangeLow(series, TimeLevel.DAY, orbLimit);
    PercentageIndicator exit = new PercentageIndicator(low,PrecisionNum.valueOf(0 - falseBreakoutOffset));
    LastCandleOfPeriod last = new LastCandleOfPeriod(series, TimeLevel.DAY, 2);
    ClosePriceIndicator close = new ClosePriceIndicator(series);
    HighPriceIndicator highPrice = new HighPriceIndicator(series);
    EMAIndicator ema450 = new EMAIndicator(close, 450);
    SMAIndicator sma50 = new SMAIndicator(close, 450);
    OpenPriceIndicator open = new OpenPriceIndicator(series);

//    Rule emaRule = new WithinPercentageRule(ema50, close, PrecisionNum.valueOf(1));
//    Rule emaWithinHighLowRule = new WithinRangeRule(entry, exit,ema450);
    Rule ema50WithinHighLowRule = new WithinRangeRule(entry, exit,ema450);

    return new BaseStrategy(
        new CrossedUpIndicatorRule(close, entry)
            .and(new UnderIndicatorRule(new DifferenceRatioIndicator(entry, exit), PrecisionNum.valueOf(2.5))),
//            .and(new NotRule(emaWithinHighLowRule))
//            .and(new NotRule(emaRule)),
        new CrossedDownIndicatorRule(close, exit)
            .or(new TrailingStopLossRule(close, PrecisionNum.valueOf(1)))
            .or(new StopGainRule(close, 4))
            .or(new IsEqualRule(close, last))
    );
  }

  private static Strategy createOpeningPriceBreakoutBearish(BarSeries series) {
    OpeningRangeHigh high = new OpeningRangeHigh(series, TimeLevel.DAY, orbLimit);
    PercentageIndicator entry = new PercentageIndicator(high, PrecisionNum.valueOf(falseBreakoutOffset));
    OpeningRangeLow low = new OpeningRangeLow(series, TimeLevel.DAY, orbLimit);
    PercentageIndicator exit = new PercentageIndicator(low,PrecisionNum.valueOf(0 - falseBreakoutOffset));
    LastCandleOfPeriod last = new LastCandleOfPeriod(series, TimeLevel.DAY, 2);
    ClosePriceIndicator close = new ClosePriceIndicator(series);
    LowPriceIndicator lowPriceIndicator = new LowPriceIndicator(series);
    EMAIndicator ema450 = new EMAIndicator(close, 450);
    EMAIndicator ema50 = new EMAIndicator(close, 50);
    SMAIndicator sma50 = new SMAIndicator(close, 450);
    Rule emaRule = new WithinPercentageRule(ema50, close, PrecisionNum.valueOf(1));
    Rule emaWithinHighLowRule = new WithinRangeRule(entry, exit,ema450);

    return new BaseStrategy(
        new CrossedDownIndicatorRule(close, exit)
            .and(new UnderIndicatorRule(new DifferenceRatioIndicator(entry, exit), PrecisionNum.valueOf(2.5))),
//            .and(new NotRule(emaWithinHighLowRule))
//            .and(new NotRule(emaRule)),
        new CrossedUpIndicatorRule(close, exit)
            .or(new TrailingStopLossRule(close, PrecisionNum.valueOf(1)))
            .or(new StopGainRule(close, 4))
            .or(new IsEqualRule(close, last))
    );
  }

  @Override
  public Strategy getBuyStrategy(Instrument tradingIdentity,
      Map<Instrument, BarSeries> BarSeriesMap) {
    return createOpeningPriceBreakoutBullish(BarSeriesMap.get(tradingIdentity));
  }

  @Override
  public Strategy getSellStrategy(Instrument tradingIdentity,
      Map<Instrument, BarSeries> BarSeriesMap) {
    return createOpeningPriceBreakoutBearish(BarSeriesMap.get(tradingIdentity));
  }

  @Override
  public TradeDirection getTradeDirection() {
    return TradeDirection.Both;
  }

  @Override
  public String getName() {
    return "ORB";
  }
}
