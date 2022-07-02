package com.dtech.kitecon.strategy.backtest;

import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.data.StrategyParameters;
import com.dtech.kitecon.misc.StrategyEnvironment;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.repository.StrategyParametersRepository;
import com.dtech.kitecon.strategy.TradingStrategy;
import com.dtech.kitecon.strategy.builder.StrategyBuilder;
import com.dtech.kitecon.strategy.builder.StrategyConfig;
import com.dtech.kitecon.strategy.dataloader.InstrumentDataLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.ta4j.core.*;
import org.ta4j.core.Trade.TradeType;
import org.ta4j.core.analysis.criteria.*;
import org.ta4j.core.analysis.criteria.pnl.AverageLossCriterion;
import org.ta4j.core.analysis.criteria.pnl.AverageProfitCriterion;
import org.ta4j.core.analysis.criteria.pnl.GrossProfitCriterion;
import org.ta4j.core.analysis.criteria.pnl.ProfitLossCriterion;
import org.ta4j.core.num.DecimalNum;

import java.util.*;

@RequiredArgsConstructor
@Component
public class BackTestingHandler {

  private final InstrumentRepository instrumentRepository;
  private final StrategyParametersRepository strategyParametersRepository;
  private final InstrumentDataLoader instrumentDataLoader;
  String[] exchanges = new String[]{"NSE", "NFO"};

  public BacktestSummary execute(String instrumentName, StrategyBuilder strategyBuilder,
      String interval) {
    Instrument tradingIdentity = instrumentRepository
        .findByTradingsymbolAndExchangeIn(instrumentName, exchanges);
    StrategyEnvironment strategyEnvironment = StrategyEnvironment.DEV;

    StrategyConfig config = getStrategyConfig(instrumentName,
        strategyBuilder, strategyEnvironment);

    Map<Instrument, BarSeries> BarSeriesMap = instrumentDataLoader.loadData(instrumentName,
        interval);

    TradingStrategy strategy = strategyBuilder.build(tradingIdentity, BarSeriesMap, config);

    BarSeries barSeries = BarSeriesMap.get(tradingIdentity);
    BarSeriesManager seriesManager = new BarSeriesManager(barSeries);
    List<BacktestResult> results = new ArrayList<>();

    Map<String, Map<?, ?>> summary = new HashMap<>();

    if (strategy.getTradeDirection().isBuy()) {
      BacktestResult backtestResult = runBacktestOnTa4jStrategy(tradingIdentity, barSeries,
          strategy.getBuyStrategy(), seriesManager, TradeType.BUY);
      results.add(backtestResult);
      summary.put("Buy", backtestResult.getAggregatesResults());

    }
    if (strategy.getTradeDirection().isSell()) {
      BacktestResult backtestResult = runBacktestOnTa4jStrategy(tradingIdentity, barSeries,
          strategy.getSellStrategy(), seriesManager, TradeType.SELL);
      results.add(backtestResult);
      summary.put("Sell", backtestResult.getAggregatesResults());
    }

    return BacktestSummary.builder().results(results).summary(summary).build();
  }

  public StrategyConfig getStrategyConfig(String instrumentName, StrategyBuilder strategyBuilder,
      StrategyEnvironment strategyEnvironment) {
    List<StrategyParameters> strategyParameters = strategyParametersRepository
        .findByStrategyNameAndInstrumentNameAndEnvironment(strategyBuilder.getName(),
            instrumentName,
            strategyEnvironment);
    StrategyConfig config = StrategyConfig.builder().params(strategyParameters).build();
    return config;
  }

  private BacktestResult runBacktestOnTa4jStrategy(Instrument tradingIdentity,
      BarSeries barSeries, Strategy strategy,
      BarSeriesManager seriesManager, TradeType orderType) {
    TradingRecord tradingRecord = seriesManager
        .run(strategy, orderType, DecimalNum.valueOf(1));
    List<Position> trades = tradingRecord.getPositions();
    return new BacktestResult(backtest(barSeries, tradingRecord), trades);
  }

//  private TradeRecord mapTradeRecord(Trade trade, BarSeries barSeries) {
//    return TradeRecord.builder()
//        .entry(buildOrder(trade.getEntry(), barSeries))
//        .exit(buildOrder(trade.getExit(), barSeries))
//        .profit(trade.getProfit().doubleValue()).build();
//  }
//
//  private OrderRecord buildOrder(Order order, BarSeries barSeries) {
//    return OrderRecord.builder()
//        .amount(order.getAmount().doubleValue())
//        .cost(order.getCost().doubleValue())
//        .index(order.getIndex())
//        .netPrice(order.getNetPrice().doubleValue())
//        .pricePerAsset(order.getPricePerAsset().doubleValue())
//        .type(order.getType())
//        .dateTime(barSeries.getBar(order.getIndex()).getEndTime())
//        .build();
//  }

  private Map<String, Double> backtest(BarSeries series, TradingRecord tradingRecord) {
    //FIXME Criterion should have a method to get name. This map population is pathetic.
    Map<String, Double> backtestresultsMap = new LinkedHashMap<>();
    backtestresultsMap.put("AverageProfitableTrades",
            calculateCriterion(new AverageLossCriterion(), series, tradingRecord));
    backtestresultsMap.put("AverageProfit",
            calculateCriterion(new AverageProfitCriterion(), series, tradingRecord));
    backtestresultsMap.put("BuyAndHold",
            calculateCriterion(new BuyAndHoldReturnCriterion(), series, tradingRecord));
    backtestresultsMap.put("LinearTransactionCost",
            calculateCriterion(new LinearTransactionCostCriterion(5000, 0.005), series, tradingRecord));
    backtestresultsMap.put("MaximumDrawdown",
            calculateCriterion(new MaximumDrawdownCriterion(), series, tradingRecord));
    backtestresultsMap.put("NumberOfBars",
            calculateCriterion(new NumberOfBarsCriterion(), series, tradingRecord));
    backtestresultsMap.put("NumberOfTrades",
            calculateCriterion(new NumberOfPositionsCriterion(), series, tradingRecord));
    backtestresultsMap.put("RewardRiskRatio",
            calculateCriterion(new ReturnOverMaxDrawdownCriterion(), series, tradingRecord));
    backtestresultsMap.put("TotalProfit",
            calculateCriterion(new GrossProfitCriterion(), series, tradingRecord));
    backtestresultsMap.put("ProfitLoss",
            calculateCriterion(new ProfitLossCriterion(), series, tradingRecord));
    return backtestresultsMap;
  }


  private Double calculateCriterion(AnalysisCriterion criterion, BarSeries series,
      TradingRecord tradingRecord) {
    System.out.println("-- " + criterion.getClass().getSimpleName() + " --");
    return criterion.calculate(series, tradingRecord).doubleValue();
  }


}
