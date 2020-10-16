package com.dtech.algo.indicators;

import com.dtech.algo.registry.common.BaseRegistry;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.candles.DojiIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.ConstantIndicator;
import org.ta4j.core.indicators.range.OpeningRangeLow;

import javax.annotation.PostConstruct;


@Service
public class IndicatorRegistry extends BaseRegistry<Indicator, IndicatorInfo> {

  @PostConstruct
  public void initialize() {
    add(OpeningRangeLow.class);
    add(SMAIndicator.class);
    add(ClosePriceIndicator.class);
    add(ConstantIndicator.class);
    add(DojiIndicator.class);
    add(RSIIndicator.class);
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
