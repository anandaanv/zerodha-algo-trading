package com.dtech.algo.strategy.units;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.strategy.builder.cache.ThreadLocalCache;
import com.dtech.algo.strategy.config.IndicatorConfig;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.function.Function;

public abstract class AbstractObjectBuilder<T, C> {
    protected <T, I> T[] resolveParameters(List<I> inputs, Function<I, T> function) {
      T[] params = (T[]) new Object[inputs.size()];
      for (int i = 0, inputsSize = inputs.size(); i < inputsSize; i++) {
        I input = inputs.get(i);
        params[i] = function.apply(input);
      }
      return params;
    }

    protected <I> Class[] resolveClasses(List<I> inputs, Function<I, Class> function) {
      Class[] params = new Class[inputs.size()];
      for (int i = 0, inputsSize = inputs.size(); i < inputsSize; i++) {
        I input = inputs.get(i);
        params[i] = function.apply(input);
      }
      return params;
    }


    protected T getObjectInternal(String key, C config, ThreadLocalCache<String, T> objectCache) throws StrategyException {
        T cachedIndicator = objectCache.get(key);
        if (cachedIndicator != null) {
            return cachedIndicator;
        }
        try {
            T value = buildObject(config);
            objectCache.put(key, value);
            return value;
        } catch (Exception ex) {
            throw new StrategyException("Error occurred while constructing an indicator", ex);
        }
    }

    protected abstract T buildObject(C config) throws StrategyException;

    }
