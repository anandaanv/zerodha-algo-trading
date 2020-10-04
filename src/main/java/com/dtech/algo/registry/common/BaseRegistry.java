package com.dtech.algo.registry.common;

import com.google.common.base.CaseFormat;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;
import org.ta4j.core.num.DoubleNum;
import org.ta4j.core.num.Num;

public class BaseRegistry {

  @Nullable
  protected static String camelToLower(String simpleName) {
    String key = CaseFormat.UPPER_CAMEL.converterTo(CaseFormat.LOWER_HYPHEN)
        .convert(simpleName);
    return key;
  }

  protected String getTypeName(Parameter parameter) {
    Class<?> type = parameter.getType();
    if (type.isPrimitive()) {
      return type.getName();
    } else if (type.isAssignableFrom(DoubleNum.class)) {
      return BaseRegistry.camelToLower(Num.class.getSimpleName());
    } {
      return BaseRegistry.camelToLower(type.getSimpleName());
    }
  }

  protected List<String> getValues(Parameter parameter) {
    Class<?> type = parameter.getType();
    if (type.isEnum()) {
      Enum[] enumConstants = (Enum[]) type.getEnumConstants();
      return Arrays.stream(enumConstants).map(Enum::name)
          .collect(Collectors.toList());
    }
    return null;
  }

  protected List<ConstructorArgs> mapConstructorArgs(Constructor constructor) {
    List<ConstructorArgs> conArgs = Arrays.stream(constructor.getParameters()).map(parameter -> {
      ConstructorArgs constructorArgs = ConstructorArgs.builder()
          .type(getTypeName(parameter))
          .name(parameter.getName())
          .values(getValues(parameter))
          .build();
      return constructorArgs;
    }).collect(Collectors.toList());
    return conArgs;
  }
}
