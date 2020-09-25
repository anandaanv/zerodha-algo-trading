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
public class OpeningRangeBreakoutStrategyBuilder extends BaseStrategyBuilder {

  private static class OrbConfig {
    Integer lastCandleOfDay;
    Double trailingStoplossPercentage;
    Double maxSizeOfOpeningCandle;
    Integer numOpeningCandles;
    Double falseBreakoutOffset;
    Double targetGainPercentage;
  }

  private Strategy createOpeningPriceBreakoutBullish(BarSeries series,
      Map<String, String> map) {
    OrbConfig config = readConfig(map);
    PercentageIndicator openingRangeHigh = openingRangeHigh(series, config);
    PercentageIndicator openingRangeLow = openingRangeLow(series, config);
    LastCandleOfPeriod last = new LastCandleOfPeriod(series, TimeLevel.DAY, 2);
    ClosePriceIndicator close = new ClosePriceIndicator(series);

    return new BaseStrategy(
        new CrossedUpIndicatorRule(close, openingRangeHigh)
            .and(verifySizeOfOpeningCandle(openingRangeHigh, openingRangeLow, config)),
        new CrossedDownIndicatorRule(close, openingRangeLow)
            .or(new TrailingStopLossRule(close, PrecisionNum.valueOf(config.trailingStoplossPercentage)))
            .or(new StopGainRule(close, config.targetGainPercentage))
            .or(new IsEqualRule(close, last))
    );
  }

  @NotNull
  private UnderIndicatorRule verifySizeOfOpeningCandle(PercentageIndicator openingRangeHigh,
      PercentageIndicator openingRangeLow,
      OrbConfig config) {
    return new UnderIndicatorRule(new DifferenceRatioIndicator(openingRangeHigh, openingRangeLow),
        PrecisionNum.valueOf(config.maxSizeOfOpeningCandle));
  }

  @NotNull
  private PercentageIndicator openingRangeLow(BarSeries series,
      OrbConfig config) {
    OpeningRangeLow low = new OpeningRangeLow(series, TimeLevel.DAY, config.numOpeningCandles);
    return new PercentageIndicator(low, PrecisionNum.valueOf(0 - config.falseBreakoutOffset));
  }

  private Strategy createOpeningPriceBreakoutBearish(BarSeries series,
      Map<String, String> map) {
    OrbConfig config = readConfig(map);
    PercentageIndicator openingRangeHigh = openingRangeHigh(series, config);
    PercentageIndicator openingRangeLow = openingRangeLow(series, config);
    LastCandleOfPeriod last = new LastCandleOfPeriod(series, TimeLevel.DAY, 2);
    ClosePriceIndicator close = new ClosePriceIndicator(series);
    return new BaseStrategy(
        new CrossedDownIndicatorRule(close, openingRangeLow)
            .and(verifySizeOfOpeningCandle(openingRangeHigh, openingRangeLow, config)),
        new CrossedUpIndicatorRule(close, openingRangeHigh)
            .or(new TrailingStopLossRule(close, PrecisionNum.valueOf(config.trailingStoplossPercentage)))
            .or(new StopGainRule(close, config.targetGainPercentage))
            .or(new IsEqualRule(close, last))
    );
  }

  @NotNull
  private PercentageIndicator openingRangeHigh(BarSeries series, OrbConfig config) {
    OpeningRangeHigh high = new OpeningRangeHigh(series, TimeLevel.DAY, config.numOpeningCandles);
    return new PercentageIndicator(high, PrecisionNum.valueOf(config.falseBreakoutOffset));
  }

  @Override
  public Strategy getBuyStrategy(Instrument tradingIdentity,
      Map<Instrument, BarSeries> BarSeriesMap,
      Map<String, String> config) {
    return createOpeningPriceBreakoutBullish(BarSeriesMap.get(tradingIdentity), config);
  }

  @Override
  public Strategy getSellStrategy(Instrument tradingIdentity,
      Map<Instrument, BarSeries> BarSeriesMap,
      Map<String, String> config) {
    return createOpeningPriceBreakoutBearish(BarSeriesMap.get(tradingIdentity), config);
  }

  @Override
  public TradeDirection getTradeDirection() {
    return TradeDirection.Both;
  }

  @Override
  public String getName() {
    return "ORB";
  }

  private OrbConfig readConfig(Map<String, String> map) {
    OrbConfig config = new OrbConfig();
    config.falseBreakoutOffset = Double.parseDouble(map.get("FALSE_BREAKOUT_RANGE"));
    config.maxSizeOfOpeningCandle = Double.parseDouble(map.get("MAX_SIZE_OF_OPENING_CANDLE"));
    config.trailingStoplossPercentage = Double.parseDouble(map.get("TRAILING_STOPLOSS_PERCENTAGE"));
    config.numOpeningCandles = Integer.parseInt(map.get("NUM_OPENING_CANDLES"));
    config.targetGainPercentage = Double.parseDouble(map.get("TARGET_GAIN_PERCENTAGE"));
    config.lastCandleOfDay = Integer.parseInt(map.get("LAST_CANDLE_OF_DAY"));
    return config;
  }
}
