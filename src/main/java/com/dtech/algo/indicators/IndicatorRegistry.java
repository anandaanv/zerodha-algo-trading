package com.dtech.algo.indicators;

import com.dtech.algo.registry.common.BaseRegistry;
import jakarta.annotation.PostConstruct;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.springframework.stereotype.Service;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.AbstractIndicator;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


@Service
public class IndicatorRegistry extends BaseRegistry<Indicator, IndicatorInfo> {

  public static Set<Class<? extends AbstractIndicator>> getClassesFromPackage(String packageName) {
    Reflections reflections = new Reflections(packageName, new SubTypesScanner(false));
    return reflections.getSubTypesOf(AbstractIndicator.class);
  }

  @PostConstruct
  public void initialize() {
    Set<Class<? extends AbstractIndicator>> indicators = getClassesFromPackage("org.ta4j.core.indicators");
    indicators.stream().forEach(this::add);
  }

  public Class<? extends Indicator> getIndicatorClass(String name) {
    return registryMap.get(name);
  }

  public IndicatorInfo getObjectInfo(String name) {
    Class aClass = getIndicatorClass(name);
    String className = camelToLower(aClass.getSimpleName());
    Constructor[] constructors = aClass.getConstructors();
    List<IndicatorConstructor> indicatorConstructors = Arrays.stream(constructors)
            .map(constructor -> IndicatorConstructor.builder()
                    .args(mapConstructorArgs(constructor))
                    .build())
            .collect(Collectors.toList());
    return IndicatorInfo.builder()
            .constructors(indicatorConstructors)
            .name(className)
            .build();
  }
}
