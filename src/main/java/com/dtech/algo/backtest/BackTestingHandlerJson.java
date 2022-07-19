package com.dtech.algo.backtest;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.algo.strategy.TradeStrategy;
import com.dtech.algo.strategy.builder.StrategyBuilderIfc;
import com.dtech.algo.strategy.builder.ifc.BarSeriesLoader;
import com.dtech.algo.strategy.config.BarSeriesConfig;
import com.dtech.algo.strategy.config.StrategyConfig;
import lombok.RequiredArgsConstructor;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.springframework.stereotype.Component;
import org.ta4j.core.*;
import org.ta4j.core.Trade.TradeType;
import org.ta4j.core.analysis.criteria.*;
import org.ta4j.core.analysis.criteria.pnl.AverageLossCriterion;
import org.ta4j.core.analysis.criteria.pnl.AverageProfitCriterion;
import org.ta4j.core.analysis.criteria.pnl.GrossProfitCriterion;
import org.ta4j.core.analysis.criteria.pnl.ProfitLossCriterion;
import org.ta4j.core.indicators.AbstractIndicator;
import org.ta4j.core.num.DecimalNum;

import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.dtech.algo.indicators.IndicatorRegistry.getClassesFromPackage;

@RequiredArgsConstructor
@Component
public class BackTestingHandlerJson {

  private final BarSeriesLoader barSeriesLoader;
  private final StrategyBuilderIfc strategyBuilder;


  public BacktestResult execute(BacktestInput backtestInput) throws StrategyException {
    List<BarSeriesConfig> barSeriesConfigs = backtestInput.getBarSeriesConfigs();
//    barSeriesConfigs.forEach(barSeriesLoader::loadBarSeries);
    BarSeriesConfig barSeriesConfig =
            barSeriesConfigs.stream().filter(config -> config.getName().equals(backtestInput.getBarSeriesName())).findFirst().get();
    IntervalBarSeries barSeriesToTrade = barSeriesLoader.loadBarSeries(barSeriesConfig);
    StrategyConfig strategyConfig = backtestInput.getStrategyConfig();
    TradeStrategy tradeStrategy = strategyBuilder.buildStrategy(strategyConfig);
    TradeType orderType = strategyConfig.getDirection().isBuy()? TradeType.BUY : TradeType.SELL;
    return runBacktestOnTa4jStrategy(barSeriesToTrade, tradeStrategy, orderType);
  }

  private BacktestResult runBacktestOnTa4jStrategy(BarSeries barSeries, TradeStrategy strategy, TradeType orderType) {
    TradingRecord tradingRecord = new BarSeriesManager(barSeries)
        .run(strategy, orderType, DecimalNum.valueOf(1));
    List<Position> trades = tradingRecord.getPositions();
    return new BacktestResult(backtest(barSeries, tradingRecord), trades);
  }

//  private Position mapTradeRecord(Position trade, BarSeries barSeries) {
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

  public static Set<Class<? extends AbstractAnalysisCriterion>> getClassesFromPackage(String packageName) {
    Reflections reflections = new Reflections(packageName, new SubTypesScanner(false));
    return reflections.getSubTypesOf(AbstractAnalysisCriterion.class);
  }

  private Map<String, Double> backtest(BarSeries series, TradingRecord tradingRecord) {
    //FIXME Criterion should have a method to get name. This map population is pathetic.
    Map<String, Double> backtestresultsMap = new LinkedHashMap<>();

    Set<Class<? extends AbstractAnalysisCriterion>> criterias = getClassesFromPackage("org.ta4j.core.analysis.criteria.pnl");
    criterias.addAll(getClassesFromPackage("org.ta4j.core.analysis.criteria"));
    criterias.forEach(criteria -> {
      try {
        AbstractAnalysisCriterion constructor = criteria.getDeclaredConstructor().newInstance();
        if (constructor != null) {
          backtestresultsMap.put(criteria.getSimpleName(),
                  calculateCriterion(constructor, series, tradingRecord));
        }
      } catch (InstantiationException e) {
        e.printStackTrace();
      } catch (IllegalAccessException e) {
        e.printStackTrace();
      } catch (InvocationTargetException e) {
        e.printStackTrace();
      } catch (NoSuchMethodException e) {
        e.printStackTrace();
      }
    });
//
//
//
//    backtestresultsMap.put("AverageProfitableTrades",
//        calculateCriterion(new AverageLossCriterion(), series, tradingRecord));
//    backtestresultsMap.put("AverageProfit",
//        calculateCriterion(new AverageProfitCriterion(), series, tradingRecord));
//    backtestresultsMap.put("BuyAndHold",
//        calculateCriterion(new BuyAndHoldReturnCriterion(), series, tradingRecord));
//    backtestresultsMap.put("LinearTransactionCost",
//        calculateCriterion(new LinearTransactionCostCriterion(5000, 0.005), series, tradingRecord));
//    backtestresultsMap.put("MaximumDrawdown",
//        calculateCriterion(new MaximumDrawdownCriterion(), series, tradingRecord));
//    backtestresultsMap.put("NumberOfBars",
//        calculateCriterion(new NumberOfBarsCriterion(), series, tradingRecord));
//    backtestresultsMap.put("NumberOfTrades",
//        calculateCriterion(new NumberOfPositionsCriterion(), series, tradingRecord));
//    backtestresultsMap.put("RewardRiskRatio",
//        calculateCriterion(new ReturnOverMaxDrawdownCriterion(), series, tradingRecord));
//    backtestresultsMap.put("TotalProfit",
//        calculateCriterion(new GrossProfitCriterion(), series, tradingRecord));
//    backtestresultsMap.put("ProfitLoss",
//        calculateCriterion(new ProfitLossCriterion(), series, tradingRecord));
    return backtestresultsMap;
  }


  private Double calculateCriterion(AnalysisCriterion criterion, BarSeries series,
      TradingRecord tradingRecord) {
    System.out.println("-- " + criterion.getClass().getSimpleName() + " --");
    return criterion.calculate(series, tradingRecord).doubleValue();
  }


}
