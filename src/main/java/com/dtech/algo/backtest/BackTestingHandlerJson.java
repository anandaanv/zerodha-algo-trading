package com.dtech.algo.backtest;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.strategy.TradeStrategy;
import com.dtech.algo.strategy.builder.StrategyBuilderIfc;
import com.dtech.algo.strategy.builder.ifc.BarSeriesLoader;
import com.dtech.algo.strategy.config.BarSeriesConfig;
import com.dtech.algo.strategy.config.StrategyConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.ta4j.core.*;
import org.ta4j.core.Order.OrderType;
import org.ta4j.core.analysis.criteria.*;
import org.ta4j.core.num.PrecisionNum;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class BackTestingHandlerJson {

  private final BarSeriesLoader barSeriesLoader;
  private final StrategyBuilderIfc strategyBuilder;


  public BacktestResult execute(BacktestInput backtestInput) throws StrategyException {
    List<BarSeriesConfig> barSeriesConfigs = backtestInput.getBarSeriesConfigs();
//    barSeriesConfigs.forEach(barSeriesLoader::loadBarSeries);
    IntervalBarSeries barSeriesToTrade = barSeriesLoader.loadBarSeries(
            BarSeriesConfig.builder().name(backtestInput.getBarSeriesName()).build());
    StrategyConfig strategyConfig = backtestInput.getStrategyConfig();
    TradeStrategy tradeStrategy = strategyBuilder.buildStrategy(strategyConfig);
    OrderType orderType = strategyConfig.getDirection().isBuy()? OrderType.BUY : OrderType.SELL;
    return runBacktestOnTa4jStrategy(barSeriesToTrade, tradeStrategy, orderType);
  }

  private BacktestResult runBacktestOnTa4jStrategy(BarSeries barSeries, TradeStrategy strategy, OrderType orderType) {
    TradingRecord tradingRecord = new BarSeriesManager(barSeries)
        .run(strategy, orderType, PrecisionNum.valueOf(1));
    List<TradeRecord> trades = tradingRecord.getTrades().stream()
        .map(trade -> mapTradeRecord(trade, barSeries))
        .collect(Collectors.toList());
    return new BacktestResult(backtest(barSeries, tradingRecord), trades);
  }

  private TradeRecord mapTradeRecord(Trade trade, BarSeries barSeries) {
    return TradeRecord.builder()
        .entry(buildOrder(trade.getEntry(), barSeries))
        .exit(buildOrder(trade.getExit(), barSeries))
        .profit(trade.getProfit().doubleValue()).build();
  }

  private OrderRecord buildOrder(Order order, BarSeries barSeries) {
    return OrderRecord.builder()
        .amount(order.getAmount().doubleValue())
        .cost(order.getCost().doubleValue())
        .index(order.getIndex())
        .netPrice(order.getNetPrice().doubleValue())
        .pricePerAsset(order.getPricePerAsset().doubleValue())
        .type(order.getType())
        .dateTime(barSeries.getBar(order.getIndex()).getEndTime())
        .build();
  }

  private Map<String, Double> backtest(BarSeries series, TradingRecord tradingRecord) {
    //FIXME Criterion should have a method to get name. This map population is pathetic.
    Map<String, Double> backtestresultsMap = new LinkedHashMap<>();
    backtestresultsMap.put("AverageProfitableTrades",
        calculateCriterion(new AverageProfitableTradesCriterion(), series, tradingRecord));
    backtestresultsMap.put("AverageProfit",
        calculateCriterion(new AverageProfitCriterion(), series, tradingRecord));
    backtestresultsMap.put("BuyAndHold",
        calculateCriterion(new BuyAndHoldCriterion(), series, tradingRecord));
    backtestresultsMap.put("LinearTransactionCost",
        calculateCriterion(new LinearTransactionCostCriterion(5000, 0.005), series, tradingRecord));
    backtestresultsMap.put("MaximumDrawdown",
        calculateCriterion(new MaximumDrawdownCriterion(), series, tradingRecord));
    backtestresultsMap.put("NumberOfBars",
        calculateCriterion(new NumberOfBarsCriterion(), series, tradingRecord));
    backtestresultsMap.put("NumberOfTrades",
        calculateCriterion(new NumberOfTradesCriterion(), series, tradingRecord));
    backtestresultsMap.put("RewardRiskRatio",
        calculateCriterion(new RewardRiskRatioCriterion(), series, tradingRecord));
    backtestresultsMap.put("TotalProfit",
        calculateCriterion(new TotalProfitCriterion(), series, tradingRecord));
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
