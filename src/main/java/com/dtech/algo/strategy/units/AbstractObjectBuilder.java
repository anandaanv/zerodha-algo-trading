package com.dtech.algo.strategy.units;

import java.util.List;
import java.util.function.Function;

public abstract class AbstractObjectBuilder {
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
}
