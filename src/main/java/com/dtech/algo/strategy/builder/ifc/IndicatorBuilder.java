package com.dtech.algo.strategy.builder.ifc;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.strategy.builder.cache.ConstantsCache;
import com.dtech.algo.strategy.builder.cache.IndicatorCache;
import com.dtech.algo.strategy.config.IndicatorConfig;
import java.lang.reflect.InvocationTargetException;
import org.ta4j.core.Indicator;

public interface IndicatorBuilder {
  Indicator getIndicator(IndicatorConfig config)
      throws NoSuchMethodException, IllegalAccessException, InvocationTargetException, InstantiationException, StrategyException;
}
