package com.dtech.algo.strategy.builder;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.strategy.TradeStrategy;
import com.dtech.algo.strategy.TradeStrategyImpl;
import com.dtech.algo.strategy.builder.cache.ConstantsCache;
import com.dtech.algo.strategy.builder.ifc.IndicatorBuilder;
import com.dtech.algo.strategy.builder.ifc.RuleBuilder;
import com.dtech.algo.strategy.config.IndicatorConfig;
import com.dtech.algo.strategy.config.RuleConfig;
import com.dtech.algo.strategy.config.StrategyConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Rule;
import org.ta4j.core.Strategy;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Service
public class FinalStrategyBuilder implements StrategyBuilderIfc {

  private final ConstantsCache constantsCache;

  private final IndicatorBuilder indicatorBuilder;

  private final RuleBuilder ruleBuilder;

  @Override
  public TradeStrategy buildStrategy(StrategyConfig strategyConfig) throws StrategyException {
    // initialize constants
    Map<String, String> constants = strategyConfig.getConstants();
    constants.forEach(constantsCache::put);

    // initialize indicators
    List<IndicatorConfig> indicators = strategyConfig.getIndicators();
    for (IndicatorConfig indicator : indicators) {
      indicatorBuilder.getIndicator(indicator);
    }

    List<RuleConfig> ruleConfigs = strategyConfig.getRules();
    for (RuleConfig ruleConfig : ruleConfigs) {
      ruleBuilder.getRule(ruleConfig);
    }

    Rule entryRule = buildFinalCriteria(strategyConfig.getEntry());
    Rule exitRule = buildFinalCriteria(strategyConfig.getExit());

    Strategy strategy = new BaseStrategy(entryRule, exitRule);
    return TradeStrategyImpl.builder()
            .delegate(strategy)
            .direction(strategyConfig.getDirection())
            .strategyName(strategyConfig.getStrategyName())
            .build();
  }

  private Rule buildFinalCriteria(List<String> list) throws StrategyException {
    Rule finalRule = null;
    for (int i = 0, listSize = list.size(); i < listSize; i++) {
      String ruleName = list.get(i);
      if (ruleName.equals("AND") || ruleName.equals("OR") || ruleName.equals("XOR")) {
        finalRule = joinRules(finalRule, ruleName, list.get(++i));
      } else {
        finalRule = getRuleFromCache(ruleName);
      }
    }
    return finalRule;
  }

  private Rule joinRules(Rule firstRule, String operation, String nextRule) throws StrategyException {
    Rule secondRule = getRuleFromCache(nextRule);
    switch (operation) {
        case "AND": return firstRule.and(secondRule);
        case "OR": return firstRule.or(secondRule);
        case "XOR": return firstRule.xor(secondRule);
        default: return firstRule;
      }
  }

  private Rule getRuleFromCache(String nextRule) throws StrategyException {
    return ruleBuilder.getRule(RuleConfig.builder().key(nextRule).build());
  }
}
