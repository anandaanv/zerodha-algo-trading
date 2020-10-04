package com.dtech.algo.rules;

import com.dtech.algo.registry.common.BaseRegistry;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.ta4j.core.Rule;
import org.ta4j.core.trading.rules.AndRule;
import org.ta4j.core.trading.rules.BooleanIndicatorRule;
import org.ta4j.core.trading.rules.BooleanRule;
import org.ta4j.core.trading.rules.CrossedDownIndicatorRule;
import org.ta4j.core.trading.rules.IsHighestRule;


@Service
public class RuleRegistry extends BaseRegistry {

  private static Map<String, Class> indicatorMap = new HashMap<>();

  static {
    add(AndRule.class);
    add(BooleanIndicatorRule.class);
    add(CrossedDownIndicatorRule.class);
    add(IsHighestRule.class);
  }

  private static void add(Class<? extends Rule> aClass) {
    String simpleName = aClass.getSimpleName();
    String key = camelToLower(simpleName);
    indicatorMap.put(key, aClass);
  }

  public Class<? extends Rule> getRuleClass(String name) {
    return indicatorMap.get(name);
  }

  public RuleInfo getRuleInfo(String name) {
    Class aClass = getRuleClass(name);
    String className = camelToLower(aClass.getSimpleName());
    Constructor[] constructors = aClass.getConstructors();
    List<RuleConstructor> indicatorConstructors = Arrays.stream(constructors)
        .map(constructor -> RuleConstructor.builder()
            .args(mapConstructorArgs(constructor))
            .build())
        .collect(Collectors.toList());
    return RuleInfo.builder()
        .constructors(indicatorConstructors)
        .name(className)
        .build();
  }

}
