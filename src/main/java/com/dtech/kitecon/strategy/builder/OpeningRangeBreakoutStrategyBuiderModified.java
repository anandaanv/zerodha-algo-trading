package com.dtech.kitecon.strategy.builder;

import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.strategy.TradeDirection;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Strategy;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.pivotpoints.TimeLevel;
import org.ta4j.core.indicators.range.LastCandleOfPeriod;
import org.ta4j.core.indicators.range.OpeningRangeHigh;
import org.ta4j.core.indicators.range.OpeningRangeLow;
import org.ta4j.core.num.PrecisionNum;
import org.ta4j.core.trading.rules.CrossedDownIndicatorRule;
import org.ta4j.core.trading.rules.CrossedUpIndicatorRule;
import org.ta4j.core.trading.rules.IsEqualRule;
import org.ta4j.core.trading.rules.OverIndicatorRule;
import org.ta4j.core.trading.rules.StopGainRule;
import org.ta4j.core.trading.rules.TrailingStopLossRule;
import org.ta4j.core.trading.rules.UnderIndicatorRule;

@Component
public class OpeningRangeBreakoutStrategyBuiderModified extends BaseStrategyBuilder {

  private static int orbLimit = 2;

  private static Strategy createOpeningPriceBreakoutBullish(BarSeries series) {
    OpeningRangeHigh entry = new OpeningRangeHigh(series, TimeLevel.DAY, orbLimit);
    OpeningRangeLow exit = new OpeningRangeLow(series, TimeLevel.DAY, orbLimit);
    ClosePriceIndicator close = new ClosePriceIndicator(series);

    EMAIndicator ema20 = new EMAIndicator(close, 20);
    EMAIndicator ema5 = new EMAIndicator(close, 5);
    LastCandleOfPeriod last = new LastCandleOfPeriod(series, TimeLevel.DAY, 2);

    return new BaseStrategy(
        new CrossedUpIndicatorRule(close, entry)
            .and(new UnderIndicatorRule(ema20, close))
            .and(new OverIndicatorRule(ema5, entry)),
        new CrossedDownIndicatorRule(close, exit)
            .or(new TrailingStopLossRule(close, PrecisionNum.valueOf(1)))
            .or(new StopGainRule(close, 5))
            .or(new IsEqualRule(close, last))
    );
  }

  private static Strategy createOpeningPriceBreakoutBearish(BarSeries series) {
    OpeningRangeLow entry = new OpeningRangeLow(series, TimeLevel.DAY, orbLimit);
    OpeningRangeHigh exit = new OpeningRangeHigh(series, TimeLevel.DAY, orbLimit);
    ClosePriceIndicator close = new ClosePriceIndicator(series);

    EMAIndicator ema20 = new EMAIndicator(close, 20);
    EMAIndicator ema5 = new EMAIndicator(close, 5);
    LastCandleOfPeriod last = new LastCandleOfPeriod(series, TimeLevel.DAY, 2);

    return new BaseStrategy(
        new CrossedDownIndicatorRule(close, entry)
            .and(new OverIndicatorRule(ema20, close))
            .and(new UnderIndicatorRule(ema5, entry)),
        new CrossedUpIndicatorRule(close, exit)
            .or(new TrailingStopLossRule(close, PrecisionNum.valueOf(1)))
            .or(new StopGainRule(close, 5))
            .or(new IsEqualRule(close, last))
    );
  }

  @Override
  public Strategy getBuyStrategy(Instrument tradingIdentity,
      Map<Instrument, BarSeries> BarSeriesMap,
      Map<String, String> config) {
    return createOpeningPriceBreakoutBullish(BarSeriesMap.get(tradingIdentity));
  }

  @Override
  public Strategy getSellStrategy(Instrument tradingIdentity,
      Map<Instrument, BarSeries> BarSeriesMap,
      Map<String, String> sell) {
    return createOpeningPriceBreakoutBearish(BarSeriesMap.get(tradingIdentity));
  }

  @Override
  public TradeDirection getTradeDirection() {
    return TradeDirection.Both;
  }

  @Override
  public String getName() {
    return "ORBNEW";
  }
}
