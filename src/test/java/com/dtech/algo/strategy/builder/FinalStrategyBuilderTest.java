package com.dtech.algo.strategy.builder;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.series.ExtendedBarSeries;
import com.dtech.algo.strategy.TradeStrategy;
import com.dtech.algo.strategy.builder.cache.BarSeriesCache;
import com.dtech.algo.strategy.builder.cache.ConstantsCache;
import com.dtech.algo.strategy.builder.ifc.BarSeriesLoader;
import com.dtech.algo.strategy.config.*;
import com.dtech.algo.strategy.units.CachedIndicatorBuilder;
import com.dtech.algo.strategy.units.CachedRuleBuilder;
import com.dtech.kitecon.KiteconApplication;

import java.util.*;

import com.dtech.kitecon.strategy.TradeDirection;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BarSeriesManager;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.analysis.criteria.TotalProfitCriterion;
import ta4jexamples.loaders.CsvTradesLoader;

@SpringBootTest(classes = {KiteconApplication.class})
class FinalStrategyBuilderTest {

  BarSeries barSeries = CsvTradesLoader.loadBitstampSeries();
  private List<IndicatorConfig> indicatorConfigs = new ArrayList<>();

  @MockBean
  private BarSeriesLoader barSeriesLoader;

  @Autowired
  private BarSeriesCache barSeriesCache;

  @Autowired
  private ConstantsCache constantsCache;

  @Autowired
  private CachedIndicatorBuilder cachedIndicatorBuilder;

  @Autowired
  private CachedRuleBuilder cachedRuleBuilder;

  @Autowired
  private StrategyBuilder strategyBuilder;

  @Test
  void buildStrategy() throws StrategyException {
    //Constants
    Map<String, String> constantsMap = new HashMap<>();

    String shortSmaBarCount = "shortSmaBarCount";
    String longSmaBarCount = "longSmaBarCount";
    String rsiBarCount = "rsiBarCount";
    String constantFive = "five";
    String constantNinetyFive = "ninetyFive";

    constantsMap.put(shortSmaBarCount, "5");
    constantsMap.put(longSmaBarCount, "200");
    constantsMap.put(rsiBarCount, "2");
    constantsMap.put(constantFive, "5");
    constantsMap.put(constantNinetyFive, "95");

    constantsMap.entrySet().forEach(entry -> {
      constantsCache.put(entry.getKey(), entry.getValue());
    });
//    constantsMap.entrySet().forEach(entry -> {
//      Mockito.doReturn(entry.getValue())
//              .when(constantsCache).get(entry.getKey());
//    });

    // setup bar series
    String barSeriesName = setupBarSeries();
    String closePriceIndicatorKey = "close-price-1";
    // ClosePriceIndicator
    setupClosePriceIndicator(barSeriesName, closePriceIndicatorKey);

    String shortSma = "short-sma";
    String longSma = "long-sma";
    String rsiName = "rsi-2";
    setupSmaIndicator(shortSmaBarCount, closePriceIndicatorKey, shortSma);
    setupSmaIndicator(longSmaBarCount, closePriceIndicatorKey, longSma);
    setupRsiIndicator(rsiBarCount, closePriceIndicatorKey, rsiName);

    RuleInput closePriceRuleInput = getIndicatorRuleInput(closePriceIndicatorKey);
    RuleInput shortSmaRuleInput = getIndicatorRuleInput(shortSma);
    RuleInput longSmaRuleInput = getIndicatorRuleInput(longSma);
    RuleInput rsiRuleInput = getIndicatorRuleInput(rsiName);
    RuleInput constantFiveInput = getConstantRuleInput(constantFive);
    RuleInput constantNinetyFiveInput = getConstantRuleInput(constantNinetyFive);

    // Entry criteria
    String shortSmaOverLongSma = "shortSmaOverLongSma";
    String rsiCrossdownFive = "rsiCrossdownFive";
    String shortSmaOverClosePrice = "shortSmaOverClosePrice";
    String overIndicatorRuleName = "over-indicator-rule";
    String crossedDownIndicatorRuleName = "crossed-down-indicator-rule";
    RuleConfig shortSmaOverLongSmaConfig = getRuleConfig(
        shortSmaRuleInput, longSmaRuleInput, shortSmaOverLongSma, overIndicatorRuleName);
    RuleConfig rsiCrossdownFiveRuleCOnfig = getRuleConfig(
        rsiRuleInput, constantFiveInput, rsiCrossdownFive, crossedDownIndicatorRuleName);
    RuleConfig shortSmaOverClosePriceRuleConfig = getRuleConfig(
        shortSmaRuleInput, closePriceRuleInput, shortSmaOverClosePrice, overIndicatorRuleName);

    // Exit criteria
    String shortSmaUnderLongSma = "shortSmaUnderLongSma";
    String rsiCrossedUpFive = "rsiCrossedUpFive";
    String shortSmaUnderClosePrice = "shortSmaUnderClosePrice";
    String underIndicatorRuleName = "under-indicator-rule";
    String crossedUpIndicatorRuleName = "crossed-up-indicator-rule";
    RuleConfig shortSmaUnderLongSmaConfig = getRuleConfig(
        shortSmaRuleInput, longSmaRuleInput, shortSmaUnderLongSma, underIndicatorRuleName);
    RuleConfig rsiCrossUpFiveRuleCOnfig = getRuleConfig(
        rsiRuleInput, constantNinetyFiveInput, rsiCrossedUpFive, crossedUpIndicatorRuleName);
    RuleConfig shortSmaUnderClosePriceRuleConfig = getRuleConfig(
        shortSmaRuleInput, closePriceRuleInput, shortSmaUnderClosePrice, underIndicatorRuleName);

    StrategyConfig config = StrategyConfig.builder()
            .constants(constantsMap)
            .direction(TradeDirection.Buy)
            .indicators(indicatorConfigs)
            .rules(Arrays.asList(shortSmaUnderLongSmaConfig, rsiCrossUpFiveRuleCOnfig, shortSmaUnderClosePriceRuleConfig,
                    shortSmaOverLongSmaConfig, rsiCrossdownFiveRuleCOnfig, shortSmaOverClosePriceRuleConfig))
            .entry(Arrays.asList(shortSmaOverLongSma, "AND", rsiCrossdownFive, "AND", shortSmaOverClosePrice))
            .exit(Arrays.asList(shortSmaUnderLongSma, "AND", rsiCrossedUpFive, "AND", shortSmaUnderClosePrice))
            .strategyName("rsi-strategy")
            .build();

    TradeStrategy tradeStrategy = strategyBuilder.buildStrategy(config);
    BarSeriesManager seriesManager = new BarSeriesManager(barSeries);
    TradingRecord tradingRecord = seriesManager.run(tradeStrategy);
    System.out.println("Number of trades for the strategy: " + tradingRecord.getTradeCount());

    // Analysis
    System.out.println(
            "Total profit for the strategy: " + new TotalProfitCriterion().calculate(barSeries, tradingRecord));  }

  @NotNull
  private RuleConfig getRuleConfig(RuleInput RuleInput1, RuleInput secondRuleInput, String ruleKey,
      String ruleName) {
    RuleConfig shortSmaOverLongSmaConfig = new RuleConfig();
    shortSmaOverLongSmaConfig.setRuleName(ruleName);
    shortSmaOverLongSmaConfig.setKey(ruleKey);
    shortSmaOverLongSmaConfig.setInputs(Arrays.asList(RuleInput1, secondRuleInput));
    return shortSmaOverLongSmaConfig;
  }

  private RuleInput getIndicatorRuleInput(String indicatorName) {
    RuleInput ruleInput = new RuleInput();
    ruleInput.setName(indicatorName);
    ruleInput.setType(RuleInputType.Indicator);
    return ruleInput;
  }

  private RuleInput getConstantRuleInput(String constantName) {
    RuleInput ruleInput = new RuleInput();
    ruleInput.setName(constantName);
    ruleInput.setType(RuleInputType.Number);
    return ruleInput;
  }

  private void setupSmaIndicator(String shortSmaBarCount, String closePriceIndicatorName,
      String shortSma) throws StrategyException {
    String indicatorName = "s-m-a-indicator";
    createBarCountBasedIndicator(shortSmaBarCount, closePriceIndicatorName, shortSma,
        indicatorName);
  }

  private void setupRsiIndicator(String shortSmaBarCount, String closePriceIndicatorName,
      String shortSma) throws StrategyException {
    String indicatorName = "r-s-i-indicator";
    createBarCountBasedIndicator(shortSmaBarCount, closePriceIndicatorName, shortSma,
        indicatorName);
  }

  private void createBarCountBasedIndicator(String shortSmaBarCount, String closePriceIndicatorName,
      String shortSma, String indicatorName) throws StrategyException {
    IndicatorConfig config = new IndicatorConfig();
    config.setKey(shortSma);
    config.setIndicatorName(indicatorName);
    IndicatorInput closePriceInput = new IndicatorInput();
    closePriceInput.setName(closePriceIndicatorName);
    closePriceInput.setType(IndicatorInputType.Indicator);
    IndicatorInput barCountInput = new IndicatorInput();
    barCountInput.setName(shortSmaBarCount);
    barCountInput.setType(IndicatorInputType.Integer);
    config.setInputs(Arrays.asList(closePriceInput, barCountInput));
    cachedIndicatorBuilder.getIndicator(config);
    indicatorConfigs.add(config);
  }

  private void setupClosePriceIndicator(String barSeriesName, String closePriceIndicatorName)
      throws StrategyException {
    IndicatorConfig closePriceIndicator = new IndicatorConfig();
    closePriceIndicator.setKey(closePriceIndicatorName);
    closePriceIndicator.setIndicatorName("close-price-indicator");
    IndicatorInput closePriceInput = new IndicatorInput();
    closePriceInput.setType(IndicatorInputType.BarSeries);
    closePriceInput.setName(barSeriesName);
    closePriceIndicator.setInputs(Collections.singletonList(closePriceInput));
    cachedIndicatorBuilder.getIndicator(closePriceIndicator);
    indicatorConfigs.add(closePriceIndicator);
  }

  @NotNull
  private String setupBarSeries() {
    ExtendedBarSeries series = ExtendedBarSeries.builder()
        .delegate(barSeries)
        .build();
    String barSeriesName = "default";
    Mockito.doReturn(series)
        .when(barSeriesLoader).loadBarSeries(
            ArgumentMatchers.argThat(
                argument -> argument.getName().equals(barSeriesName)
        ));
    barSeriesCache.put(barSeriesName, series);
    return barSeriesName;
  }
}