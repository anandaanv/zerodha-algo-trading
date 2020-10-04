package com.dtech.algo.strategy.units;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.rules.RuleRegistry;
import com.dtech.algo.strategy.builder.cache.ConstantsCache;
import com.dtech.algo.strategy.builder.cache.IndicatorCache;
import com.dtech.algo.strategy.builder.cache.RuleCache;
import com.dtech.algo.strategy.builder.ifc.RuleBuilder;
import com.dtech.algo.strategy.config.RuleConfig;
import com.dtech.algo.strategy.config.RuleInput;
import com.dtech.algo.strategy.config.RuleInputType;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.ta4j.core.Indicator;
import org.ta4j.core.Rule;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.num.Num;
import org.ta4j.core.num.PrecisionNum;

@Component
@RequiredArgsConstructor
public class CachedRuleBuilder implements RuleBuilder {

  private final IndicatorCache indicatorCache;
  private final RuleCache ruleCache;

  private final ConstantsCache constantsCache;

  private final RuleRegistry registry;

  @Override
  public Rule getRule(RuleConfig config) throws StrategyException {
    String key = config.getKey();
    if(ruleCache.get(key) != null) {
      return ruleCache.get(key);
    } else {
      try {
        String name = config.getRuleName();
        Class<? extends Rule> indicatorClass = registry.getRuleClass(name);
        List<RuleInput> inputs = config.getInputs();
        Object[] parameters = resolveParameters(inputs, this::resolveValue);
        Class[] classes = resolveClasses(inputs, this::resolveClass);
        Constructor<? extends Rule> constructor = indicatorClass.getConstructor(classes);
        Rule indicator = constructor.newInstance(parameters);
        return indicator;
      } catch (Exception ex) {
        throw new StrategyException("Error occured while constructing an indicator", ex);
      }
    }
  }

  private Class[] resolveClasses(List<RuleInput> inputs, Function<RuleInput, Class> function) {
    Class[] params = new Class[inputs.size()];
    for (int i = 0, inputsSize = inputs.size(); i < inputsSize; i++) {
      RuleInput input = inputs.get(i);
      params[i] = function.apply(input);
    }
    return params;
  }


  private <T> T[] resolveParameters(List<RuleInput> inputs, Function<RuleInput, T> function) {
    T[] params = (T[]) new Object[inputs.size()];
    for (int i = 0, inputsSize = inputs.size(); i < inputsSize; i++) {
      RuleInput input = inputs.get(i);
      params[i] = function.apply(input);
    }
    return params;
  }

  private Object resolveValue(RuleInput input) {
    String name = input.getName();
    if (input.getType() == RuleInputType.Indicator) {
      return indicatorCache.get(name);
    }  else if (input.getType() == RuleInputType.Rule) {
      return ruleCache.get(name);
    } else if (input.getType() == RuleInputType.Number) {
      Num value = PrecisionNum.valueOf(Integer.valueOf(constantsCache.get(name)));
      return value;
    }
    return null;
  }

  private Class resolveClass(RuleInput input) {
    if (input.getType() == RuleInputType.Number) {
      return Num.class;
    } else if (input.getType() == RuleInputType.Rule) {
      return Rule.class;
    } else if (input.getType() == RuleInputType.Indicator) {
      return Indicator.class;
    } else if (input.getType() == RuleInputType.TradingRecord) {
      return TradingRecord.class;
    }
    return null;
  }

}
