package com.dtech.algo.registry.common;

import com.dtech.algo.indicators.IndicatorInfo;
import com.google.common.base.CaseFormat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.stream.Collectors;

import org.jetbrains.annotations.Nullable;
import org.ta4j.core.num.DoubleNum;
import org.ta4j.core.num.Num;

public abstract class BaseRegistry<T, I> {

    protected final Map<String, Class<? extends T>> registryMap = new HashMap<>();

    public abstract I getObjectInfo(String name);

    @Nullable
    protected static String camelToLower(String simpleName) {
        return CaseFormat.UPPER_CAMEL.converterTo(CaseFormat.LOWER_HYPHEN)
                .convert(simpleName);
    }

    protected String getTypeName(Parameter parameter) {
        Class<?> type = parameter.getType();
        if (type.isPrimitive()) {
            return type.getName();
        } else if (type.isAssignableFrom(DoubleNum.class)) {
            return BaseRegistry.camelToLower(Num.class.getSimpleName());
        }
        {
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
        return Collections.emptyList();
    }

    protected List<ConstructorArgs> mapConstructorArgs(Constructor<T> constructor) {
        return Arrays.stream(constructor.getParameters())
                .map(parameter -> ConstructorArgs.builder()
                        .type(getTypeName(parameter))
                        .name(parameter.getName())
                        .values(getValues(parameter))
                        .build())
                .collect(Collectors.toList());
    }

    protected void add(Class<? extends T> aClass) {
        String simpleName = aClass.getSimpleName();
        String key = camelToLower(simpleName);
        registryMap.put(key, aClass);
    }

    public Collection<String> getAllObjectNames() {
        return registryMap.keySet();
    }
}
