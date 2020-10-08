package com.dtech.algo.strategy.units;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.indicators.IndicatorInfo;
import com.dtech.algo.indicators.IndicatorRegistry;
import com.dtech.algo.strategy.builder.cache.BarSeriesCache;
import com.dtech.algo.strategy.builder.cache.ConstantsCache;
import com.dtech.algo.strategy.builder.cache.IndicatorCache;
import com.dtech.algo.strategy.builder.ifc.IndicatorBuilder;
import com.dtech.algo.strategy.config.IndicatorConfig;
import com.dtech.algo.strategy.config.IndicatorInput;
import com.dtech.algo.strategy.config.IndicatorInputType;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.num.Num;
import org.ta4j.core.num.PrecisionNum;

@Component
@RequiredArgsConstructor
public class CachedIndicatorBuilder implements IndicatorBuilder {

  private final IndicatorCache indicatorCache;

  private final ConstantsCache constantsCache;

  private final IndicatorRegistry registry;

  private final BarSeriesCache barSeriesCache;

  @Override
  public Indicator getIndicator(IndicatorConfig config)
      throws StrategyException {
    String key = config.getKey();
    if(indicatorCache.get(key) != null) {
      return indicatorCache.get(key);
    } else {
      try {
        String name = config.getIndicatorName();
        Class<? extends Indicator> indicatorClass = registry.getIndicatorClass(name);
        IndicatorInfo indicatorInfo = registry.getIndicatorInfo(name);
        List<IndicatorInput> inputs = config.getInputs();
        Object[] parameters = resolveParameters(inputs, this::resolveValue);
        Class[] classes = resolveClasses(inputs, this::resolveClass);
        Constructor<? extends Indicator> constructor = indicatorClass.getConstructor(classes);
        Indicator indicator = constructor.newInstance(parameters);
        indicatorCache.put(key, indicator);
        return indicator;
      } catch (Exception ex) {
        throw new StrategyException("Error occured while constructing an indicator", ex);
      }
    }
  }

  private Class[] resolveClasses(List<IndicatorInput> inputs, Function<IndicatorInput, Class> function) {
    Class[] params = new Class[inputs.size()];
    for (int i = 0, inputsSize = inputs.size(); i < inputsSize; i++) {
      IndicatorInput input = inputs.get(i);
      params[i] = function.apply(input);
    }
    return params;
  }


  private <T> T[] resolveParameters(List<IndicatorInput> inputs, Function<IndicatorInput, T> function) {
    T[] params = (T[]) new Object[inputs.size()];
    for (int i = 0, inputsSize = inputs.size(); i < inputsSize; i++) {
      IndicatorInput input = inputs.get(i);
      params[i] = function.apply(input);
    }
    return params;
  }

  private Object resolveValue(IndicatorInput input) {
    String name = input.getName();
    if (input.getType() == IndicatorInputType.Number) {
      Double value = Double.valueOf(constantsCache.get(name));
      Num num = PrecisionNum.valueOf(value);
      return num;
    } else if (input.getType() == IndicatorInputType.BarSeries) {
      return barSeriesCache.get(name);
    }  else if (input.getType() == IndicatorInputType.Indicator) {
      return indicatorCache.get(name);
    }  else if (input.getType() == IndicatorInputType.Integer) {
      Integer value = Integer.valueOf(constantsCache.get(name));
      return value;
    }
    return null;
  }

  private Class resolveClass(IndicatorInput input) {
    if (input.getType() == IndicatorInputType.Number) {
      return Object.class;
    } else if (input.getType() == IndicatorInputType.BarSeries) {
      return BarSeries.class;
    } else if (input.getType() == IndicatorInputType.Integer) {
      return int.class;
    } else if (input.getType() == IndicatorInputType.Indicator) {
      return Indicator.class;
    }
    return null;
  }

}
