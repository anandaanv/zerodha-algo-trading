package com.dtech.kitecon.strategy.builder;

import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.strategy.TradeDirection;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Strategy;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.DifferenceRatioIndicator;
import org.ta4j.core.indicators.pivotpoints.TimeLevel;
import org.ta4j.core.indicators.range.LastCandleOfPeriod;
import org.ta4j.core.indicators.range.OpeningRangeHigh;
import org.ta4j.core.indicators.range.OpeningRangeLow;
import org.ta4j.core.indicators.range.PercentageIndicator;
import org.ta4j.core.num.PrecisionNum;
import org.ta4j.core.trading.rules.CrossedDownIndicatorRule;
import org.ta4j.core.trading.rules.CrossedUpIndicatorRule;
import org.ta4j.core.trading.rules.IsEqualRule;
import org.ta4j.core.trading.rules.StopGainRule;
import org.ta4j.core.trading.rules.TrailingStopLossRule;
import org.ta4j.core.trading.rules.UnderIndicatorRule;

@Component
public class OpeningRangeBreakoutStrategyBuider extends BaseStrategyBuilder {

  public static final int TRAILING_STOPLOSS_PERCENTAGE = 1;
  public static final double SIZE_OF_OPENING_CANDLE = 3;
  private static int NUM_OPENING_CANDLES = 2;
  private static double falseBreakoutOffset = 0.03;
  private static int gainPercentage = 5;

  private static Strategy createOpeningPriceBreakoutBullish(BarSeries series) {
    PercentageIndicator openingRangeHigh = openingRangeHigh(series);
    PercentageIndicator openingRangeLow = openingRangeLow(series);
    LastCandleOfPeriod last = new LastCandleOfPeriod(series, TimeLevel.DAY, 2);
    ClosePriceIndicator close = new ClosePriceIndicator(series);

    return new BaseStrategy(
        new CrossedUpIndicatorRule(close, openingRangeHigh)
            .and(verifySizeOfOpeningCandle(openingRangeHigh, openingRangeLow)),
        new CrossedDownIndicatorRule(close, openingRangeLow)
            .or(new TrailingStopLossRule(close, PrecisionNum.valueOf(TRAILING_STOPLOSS_PERCENTAGE)))
            .or(new StopGainRule(close, gainPercentage))
            .or(new IsEqualRule(close, last))
    );
  }

  @NotNull
  private static UnderIndicatorRule verifySizeOfOpeningCandle(PercentageIndicator openingRangeHigh,
      PercentageIndicator openingRangeLow) {
    return new UnderIndicatorRule(new DifferenceRatioIndicator(openingRangeHigh, openingRangeLow),
        PrecisionNum.valueOf(SIZE_OF_OPENING_CANDLE));
  }

  @NotNull
  private static PercentageIndicator openingRangeLow(BarSeries series) {
    OpeningRangeLow low = new OpeningRangeLow(series, TimeLevel.DAY, NUM_OPENING_CANDLES);
    return new PercentageIndicator(low, PrecisionNum.valueOf(0 - falseBreakoutOffset));
  }

  private static Strategy createOpeningPriceBreakoutBearish(BarSeries series) {
    PercentageIndicator openingRangeHigh = openingRangeHigh(series);
    PercentageIndicator openingRangeLow = openingRangeLow(series);
    LastCandleOfPeriod last = new LastCandleOfPeriod(series, TimeLevel.DAY, 2);
    ClosePriceIndicator close = new ClosePriceIndicator(series);
    return new BaseStrategy(
        new CrossedDownIndicatorRule(close, openingRangeLow)
            .and(verifySizeOfOpeningCandle(openingRangeHigh, openingRangeLow)),
        new CrossedUpIndicatorRule(close, openingRangeHigh)
            .or(new TrailingStopLossRule(close, PrecisionNum.valueOf(TRAILING_STOPLOSS_PERCENTAGE)))
            .or(new StopGainRule(close, gainPercentage))
            .or(new IsEqualRule(close, last))
    );
  }

  @NotNull
  private static PercentageIndicator openingRangeHigh(BarSeries series) {
    OpeningRangeHigh high = new OpeningRangeHigh(series, TimeLevel.DAY, NUM_OPENING_CANDLES);
    return new PercentageIndicator(high, PrecisionNum.valueOf(falseBreakoutOffset));
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
