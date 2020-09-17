/*******************************************************************************
 * The MIT License (MIT)
 *
 * Copyright (c) 2014-2017 Marc de Verdelhan, 2017-2018 Ta4j Organization & respective
 * authors (see AUTHORS)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *******************************************************************************/
package com.dtech.kitecon.strategy;

import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.ta4j.core.AnalysisCriterion;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BarSeriesManager;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Order;
import org.ta4j.core.Strategy;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.analysis.criteria.AverageProfitCriterion;
import org.ta4j.core.analysis.criteria.AverageProfitableTradesCriterion;
import org.ta4j.core.analysis.criteria.BuyAndHoldCriterion;
import org.ta4j.core.analysis.criteria.LinearTransactionCostCriterion;
import org.ta4j.core.analysis.criteria.MaximumDrawdownCriterion;
import org.ta4j.core.analysis.criteria.NumberOfBarsCriterion;
import org.ta4j.core.analysis.criteria.NumberOfTradesCriterion;
import org.ta4j.core.analysis.criteria.ProfitLossCriterion;
import org.ta4j.core.analysis.criteria.RewardRiskRatioCriterion;
import org.ta4j.core.analysis.criteria.TotalProfitCriterion;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;
import org.ta4j.core.num.PrecisionNum;
import org.ta4j.core.trading.rules.OverIndicatorRule;
import org.ta4j.core.trading.rules.UnderIndicatorRule;

@Component
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class SimpleMovingAverageBacktesting {

  private final BarsLoader barsLoader;
  private final InstrumentRepository instrumentRepository;

  private static void calculateCriterion(AnalysisCriterion criterion, BarSeries series,
      TradingRecord tradingRecord3DaySmaUnder, TradingRecord tradingRecord3DaySmaOver) {
    System.out.println("-- " + criterion.getClass().getSimpleName() + " --");
    Num calculate3DaySmaUnder = criterion.calculate(series, tradingRecord3DaySmaUnder);
    Num calculate3DaySmaOver = criterion.calculate(series, tradingRecord3DaySmaOver);
    System.out.println(calculate3DaySmaUnder);
    System.out.println(calculate3DaySmaOver);
    System.out.println();
  }

  private static ZonedDateTime CreateDay(int day) {
    return ZonedDateTime.of(2018, 01, day, 12, 0, 0, 0, ZoneId.systemDefault());
  }

  private static Strategy create3DaySmaUnderStrategy(BarSeries series) {
    ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
    SMAIndicator sma = new SMAIndicator(closePrice, 3);
    return new BaseStrategy(
        new UnderIndicatorRule(sma, closePrice),
        new OverIndicatorRule(sma, closePrice)
    );
  }

  private static Strategy create3DaySmaOverStrategy(BarSeries series) {
    ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
    SMAIndicator sma = new SMAIndicator(closePrice, 3);
    return new BaseStrategy(
        new OverIndicatorRule(sma, closePrice),
        new UnderIndicatorRule(sma, closePrice)
    );
  }

  public void execute(Long instrumentId) throws InterruptedException {
    Instrument instrument = null;// instrumentRepository.getOne(instrumentId);
    BarSeries series = barsLoader.loadInstrumentSeries(instrument);

    Strategy strategy3DaySmaUnder = create3DaySmaUnderStrategy(series);

    BarSeriesManager seriesManager = new BarSeriesManager(series);
    TradingRecord tradingRecord3DaySmaUnder = seriesManager
        .run(strategy3DaySmaUnder, Order.OrderType.BUY, PrecisionNum.valueOf(50));
    System.out.println(tradingRecord3DaySmaUnder);

    Strategy strategy3DaySmaOver = create3DaySmaOverStrategy(series);
    TradingRecord tradingRecord3DaySmaOver = seriesManager
        .run(strategy3DaySmaOver, Order.OrderType.BUY, PrecisionNum.valueOf(50));
    System.out.println(tradingRecord3DaySmaOver);

    calculateCriterion(new AverageProfitableTradesCriterion(), series, tradingRecord3DaySmaUnder,
        tradingRecord3DaySmaOver);
    calculateCriterion(new AverageProfitCriterion(), series, tradingRecord3DaySmaUnder,
        tradingRecord3DaySmaOver);
    calculateCriterion(new BuyAndHoldCriterion(), series, tradingRecord3DaySmaUnder,
        tradingRecord3DaySmaOver);
    calculateCriterion(new LinearTransactionCostCriterion(5000, 0.005), series,
        tradingRecord3DaySmaUnder, tradingRecord3DaySmaOver);
    calculateCriterion(new MaximumDrawdownCriterion(), series, tradingRecord3DaySmaUnder,
        tradingRecord3DaySmaOver);
    calculateCriterion(new NumberOfBarsCriterion(), series, tradingRecord3DaySmaUnder,
        tradingRecord3DaySmaOver);
    calculateCriterion(new NumberOfTradesCriterion(), series, tradingRecord3DaySmaUnder,
        tradingRecord3DaySmaOver);
    calculateCriterion(new RewardRiskRatioCriterion(), series, tradingRecord3DaySmaUnder,
        tradingRecord3DaySmaOver);
    calculateCriterion(new TotalProfitCriterion(), series, tradingRecord3DaySmaUnder,
        tradingRecord3DaySmaOver);
    calculateCriterion(new ProfitLossCriterion(), series, tradingRecord3DaySmaUnder,
        tradingRecord3DaySmaOver);
  }
}
