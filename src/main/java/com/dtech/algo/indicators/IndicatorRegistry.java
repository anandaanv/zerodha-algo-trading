package com.dtech.algo.indicators;

import com.google.common.base.CaseFormat;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.ConstantIndicator;
import org.ta4j.core.indicators.range.OpeningRangeLow;
import org.ta4j.core.num.DoubleNum;
import org.ta4j.core.num.Num;


@Service
public class IndicatorRegistry {

  private static Map<String, Class> indicatorMap = new HashMap<>();

  static {
    add(OpeningRangeLow.class);
    add(SMAIndicator.class);
    add(ClosePriceIndicator.class);
    add(ConstantIndicator.class);
  }

  private static void add(Class<? extends Indicator> aClass) {
    String simpleName = aClass.getSimpleName();
    String key = camelToLower(simpleName);
    indicatorMap.put(key, aClass);
  }

  @Nullable
  private static String camelToLower(String simpleName) {
    String key = CaseFormat.UPPER_CAMEL.converterTo(CaseFormat.LOWER_HYPHEN)
        .convert(simpleName);
    return key;
  }

  public Class<? extends Indicator> getIndicatorClass(String name) {
    return indicatorMap.get(name);
  }

  public IndicatorInfo getIndicatorInfo(String name) {
    Class aClass = getIndicatorClass(name);
    String className = camelToLower(aClass.getSimpleName());
    Constructor[] constructors = aClass.getConstructors();
    List<IndicatorConstructor> indicatorConstructors = Arrays.stream(constructors).map(constructor -> {
      List<ConstructorArgs> conArgs = Arrays.stream(constructor.getParameters()).map(parameter -> {
        ConstructorArgs constructorArgs = ConstructorArgs.builder()
            .type(getTypeName(parameter))
            .name(parameter.getName())
            .values(getValues(parameter))
            .build();
        return constructorArgs;
      }).collect(Collectors.toList());
      return IndicatorConstructor.builder()
          .args(conArgs)
          .build();
    }).collect(Collectors.toList());
    return IndicatorInfo.builder()
        .constructors(indicatorConstructors)
        .name(className)
        .build();
  }

  private List<String> getValues(Parameter parameter) {
    Class<?> type = parameter.getType();
    if (type.isEnum()) {
      Enum[] enumConstants = (Enum[]) type.getEnumConstants();
      return Arrays.stream(enumConstants).map(Enum::name)
          .collect(Collectors.toList());
    }
    return null;
  }

  private String getTypeName(Parameter parameter) {
    Class<?> type = parameter.getType();
    if (type.isPrimitive()) {
      return type.getName();
    } else if (type.isAssignableFrom(DoubleNum.class)) {
      return camelToLower(Num.class.getSimpleName());
    } {
      return camelToLower(type.getSimpleName());
    }
  }

}
