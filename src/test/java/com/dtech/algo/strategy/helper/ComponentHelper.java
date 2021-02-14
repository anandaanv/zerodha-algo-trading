package com.dtech.algo.strategy.helper;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.indicators.IndicatorConstructor;
import com.dtech.algo.indicators.IndicatorInfo;
import com.dtech.algo.registry.common.ConstructorArgs;
import com.dtech.algo.rules.RuleConstructor;
import com.dtech.algo.rules.RuleInfo;
import com.dtech.algo.series.ExtendedBarSeries;
import com.dtech.algo.strategy.builder.cache.BarSeriesCache;
import com.dtech.algo.strategy.builder.cache.ConstantsCache;
import com.dtech.algo.strategy.builder.ifc.BarSeriesLoader;
import com.dtech.algo.strategy.config.*;
import com.dtech.algo.strategy.units.CachedIndicatorBuilder;
import com.dtech.kitecon.strategy.TradeDirection;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import ta4jexamples.loaders.CsvTradesLoader;

import java.util.*;

@Component
public class ComponentHelper {

    BarSeries barSeries = CsvTradesLoader.loadBitstampSeries();

    private final CachedIndicatorBuilder cachedIndicatorBuilder;
    private final BarSeriesLoader barSeriesLoader;
    private final BarSeriesCache barSeriesCache;
    private final ConstantsCache constantsCache;


    @Autowired
    public ComponentHelper(CachedIndicatorBuilder cachedIndicatorBuilder,
                           BarSeriesLoader barSeriesLoader, BarSeriesCache barSeriesCache,
                           ConstantsCache constantsCache) {
        this.cachedIndicatorBuilder = cachedIndicatorBuilder;
        this.barSeriesLoader = barSeriesLoader;
        this.barSeriesCache = barSeriesCache;
        this.constantsCache = constantsCache;
    }

    public RuleConfig getRuleConfig(RuleInput RuleInput1, RuleInput secondRuleInput, String ruleKey,
                                    String ruleName) {
        RuleConfig shortSmaOverLongSmaConfig = new RuleConfig();
        shortSmaOverLongSmaConfig.setRuleName(ruleName);
        shortSmaOverLongSmaConfig.setKey(ruleKey);
        shortSmaOverLongSmaConfig.setInputs(Arrays.asList(RuleInput1, secondRuleInput));
        return shortSmaOverLongSmaConfig;
    }

    public RuleInput getIndicatorRuleInput(String indicatorName) {
        RuleInput ruleInput = new RuleInput();
        ruleInput.setName(indicatorName);
        ruleInput.setType(RuleInputType.Indicator);
        return ruleInput;
    }

    public RuleInput getConstantRuleInput(String constantName) {
        RuleInput ruleInput = new RuleInput();
        ruleInput.setName(constantName);
        ruleInput.setType(RuleInputType.Number);
        return ruleInput;
    }

    public void setupSmaIndicator(String shortSmaBarCount, String closePriceIndicatorName,
                                  String shortSma, List<IndicatorConfig> indicatorConfigs) throws StrategyException {
        String indicatorName = "s-m-a-indicator";
        createBarCountBasedIndicator(shortSmaBarCount, closePriceIndicatorName, shortSma,
                indicatorName, indicatorConfigs);
    }

    public void setupRsiIndicator(String shortSmaBarCount, String closePriceIndicatorName,
                                  String shortSma, List<IndicatorConfig> indicatorConfigs) throws StrategyException {
        String indicatorName = "r-s-i-indicator";
        createBarCountBasedIndicator(shortSmaBarCount, closePriceIndicatorName, shortSma,
                indicatorName, indicatorConfigs);
    }

    public void createBarCountBasedIndicator(String shortSmaBarCount, String closePriceIndicatorName,
                                             String shortSma, String indicatorName,
                                             List<IndicatorConfig> indicatorConfigs) throws StrategyException {
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

    public void setupClosePriceIndicator(String barSeriesName, String closePriceIndicatorName
            , List<IndicatorConfig> indicatorConfigs)
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

    public String setupBarSeries(String barSeriesName) throws StrategyException {
        ExtendedBarSeries series = ExtendedBarSeries.builder()
                .delegate(barSeries)
                .build();
        Mockito.doReturn(series)
                .when(barSeriesLoader).loadBarSeries(
                ArgumentMatchers.argThat(
                        argument -> argument.getName().equals(barSeriesName)
                ));
        barSeriesCache.put(barSeriesName, series);
        return barSeriesName;
    }


    public BarSeries getBarSeries() {
        return barSeries;
    }

    public StrategyConfig buildSimpleSmaStrategy() throws StrategyException {
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

        constantsMap.forEach(constantsCache::put);

        List<IndicatorConfig> indicatorConfigs = new ArrayList<>();

        // setup bar series
        String barSeriesName = setupBarSeries("default");
        String closePriceIndicatorKey = "close-price-1";
        // ClosePriceIndicator
        setupClosePriceIndicator(barSeriesName, closePriceIndicatorKey, indicatorConfigs);

        String shortSma = "short-sma";
        String longSma = "long-sma";
        String rsiName = "rsi-2";
        setupSmaIndicator(shortSmaBarCount, closePriceIndicatorKey, shortSma, indicatorConfigs);
        setupSmaIndicator(longSmaBarCount, closePriceIndicatorKey, longSma, indicatorConfigs);
        setupRsiIndicator(rsiBarCount, closePriceIndicatorKey, rsiName, indicatorConfigs);

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

        return StrategyConfig.builder()
                .constants(constantsMap)
                .direction(TradeDirection.Buy)
                .indicators(indicatorConfigs)
                .rules(Arrays.asList(shortSmaUnderLongSmaConfig, rsiCrossUpFiveRuleCOnfig, shortSmaUnderClosePriceRuleConfig,
                        shortSmaOverLongSmaConfig, rsiCrossdownFiveRuleCOnfig, shortSmaOverClosePriceRuleConfig))
                .entry(Arrays.asList(shortSmaOverLongSma, "AND", rsiCrossdownFive, "AND", shortSmaOverClosePrice))
                .exit(Arrays.asList(shortSmaUnderLongSma, "AND", rsiCrossedUpFive, "AND", shortSmaUnderClosePrice))
                .strategyName("rsi-strategy")
                .build();
    }

    public IndicatorInfo getConstantIndicatorInfo(String indicatorType, String num, String indicatorName) {
        ConstructorArgs[] args = new ConstructorArgs[2];
        args[0] = new ConstructorArgs(indicatorType, "args0", Collections.emptyList());
        args[1] = new ConstructorArgs(num, "args1", Collections.emptyList());
        List<ConstructorArgs> targs = Arrays.asList(args);
        IndicatorConstructor con = IndicatorConstructor.builder()
                .args(targs)
                .build();
        return IndicatorInfo.builder()
                .constructors(Collections.singletonList(con))
                .name(indicatorName).build();
    }

    public RuleInfo getGenericRuleInfo(String ruleName) {
        ConstructorArgs[] args = new ConstructorArgs[2];
        args[0] = new ConstructorArgs("rule", "arg0", Collections.emptyList());
        args[1] = new ConstructorArgs("rule", "arg1", Collections.emptyList());
        List<ConstructorArgs> targs = Arrays.asList(args);
        RuleConstructor con = RuleConstructor.builder()
                .args(targs)
                .build();
        RuleInfo indicatorInfo = RuleInfo.builder()
                .constructors(Collections.singletonList(con))
                .name(ruleName).build();
        return indicatorInfo;
    }
}
