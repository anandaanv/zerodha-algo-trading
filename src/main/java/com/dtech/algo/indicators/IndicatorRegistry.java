package com.dtech.algo.indicators;

import com.dtech.algo.registry.common.ConstructorArgs;
import com.dtech.algo.registry.common.BaseRegistry;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.candles.DojiIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.ConstantIndicator;
import org.ta4j.core.indicators.range.OpeningRangeLow;


@Service
public class IndicatorRegistry extends BaseRegistry {

  private static Map<String, Class> indicatorMap = new HashMap<>();

  static {
    add(OpeningRangeLow.class);
    add(SMAIndicator.class);
    add(ClosePriceIndicator.class);
    add(ConstantIndicator.class);
    add(DojiIndicator.class);
    add(RSIIndicator.class);
  }

  private static void add(Class<? extends Indicator> aClass) {
    String simpleName = aClass.getSimpleName();
    String key = camelToLower(simpleName);
    indicatorMap.put(key, aClass);
  }

  public Class<? extends Indicator> getIndicatorClass(String name) {
    return indicatorMap.get(name);
  }

  public IndicatorInfo getIndicatorInfo(String name) {
    Class aClass = getIndicatorClass(name);
    String className = camelToLower(aClass.getSimpleName());
    Constructor[] constructors = aClass.getConstructors();
    List<IndicatorConstructor> indicatorConstructors = Arrays.stream(constructors).map(constructor -> {
      return IndicatorConstructor.builder()
          .args(mapConstructorArgs(constructor))
          .build();
    }).collect(Collectors.toList());
    return IndicatorInfo.builder()
        .constructors(indicatorConstructors)
        .name(className)
        .build();
  }

}
