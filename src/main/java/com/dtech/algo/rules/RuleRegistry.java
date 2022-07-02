package com.dtech.algo.rules;

import com.dtech.algo.registry.common.BaseRegistry;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.springframework.stereotype.Service;
import org.ta4j.core.Rule;
import org.ta4j.core.indicators.AbstractIndicator;
import org.ta4j.core.rules.*;

import javax.annotation.PostConstruct;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


@Service
public class RuleRegistry extends BaseRegistry<Rule, RuleInfo> {

  public static Set<Class<? extends AbstractRule>> getClassesFromPackage(String packageName) {
    Reflections reflections = new Reflections(packageName, new SubTypesScanner(false));
    return reflections.getSubTypesOf(AbstractRule.class);
  }

  @PostConstruct
  public void initialise() {
    Set<Class<? extends AbstractRule>> indicators = getClassesFromPackage("org.ta4j.core.rules");
    indicators.stream().forEach(this::add);
  }

  public Class<? extends Rule> getRuleClass(String name) {
    return registryMap.get(name);
  }

  public RuleInfo getObjectInfo(String name) {
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
