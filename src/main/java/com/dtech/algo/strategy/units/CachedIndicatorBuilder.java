package com.dtech.algo.strategy.units;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.indicators.IndicatorRegistry;
import com.dtech.algo.strategy.builder.cache.BarSeriesCache;
import com.dtech.algo.strategy.builder.cache.ConstantsCache;
import com.dtech.algo.strategy.builder.cache.IndicatorCache;
import com.dtech.algo.strategy.builder.cache.ThreadLocalCache;
import com.dtech.algo.strategy.builder.ifc.IndicatorBuilder;
import com.dtech.algo.strategy.config.IndicatorConfig;
import com.dtech.algo.strategy.config.IndicatorInput;
import com.dtech.algo.strategy.config.IndicatorInputType;

import java.lang.reflect.Constructor;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.pivotpoints.TimeLevel;
import org.ta4j.core.num.DecimalNum;

@Component
@RequiredArgsConstructor
public class CachedIndicatorBuilder extends AbstractObjectBuilder<Indicator, IndicatorConfig> implements IndicatorBuilder {

    private final IndicatorCache indicatorCache;

    private final ConstantsCache constantsCache;

    private final IndicatorRegistry registry;

    private final BarSeriesCache barSeriesCache;

    @Override
    public Indicator getIndicator(IndicatorConfig config)
            throws StrategyException {
        return getObjectInternal(config.getKey(), config, indicatorCache);
    }

    protected Indicator buildObject(IndicatorConfig config) throws StrategyException {
        String name = config.getIndicatorName();
        Class<? extends Indicator> indicatorClass = registry.getIndicatorClass(name);
        List<IndicatorInput> inputs = config.getInputs();
        Object[] parameters = resolveParameters(inputs, this::resolveValue);
        Class[] classes = resolveClasses(inputs, this::resolveClass);
        Constructor<? extends Indicator> constructor = null;
        try {
            constructor = indicatorClass.getConstructor(classes);
            Indicator indicator = constructor.newInstance(parameters);
            return indicator;
        } catch (Exception e) {
            throw new StrategyException("error occurred while creating indicator", e);
        }
    }


    private Object resolveValue(IndicatorInput input) {
        String name = input.getName();
        if (input.getType() == IndicatorInputType.Number) {
            Double value = Double.valueOf(constantsCache.get(name));
            return DecimalNum.valueOf(value);
        } else if (input.getType() == IndicatorInputType.BarSeries) {
            return barSeriesCache.get(name);
        } else if (input.getType() == IndicatorInputType.Indicator) {
            return indicatorCache.get(name);
        }else if (input.getType() == IndicatorInputType.TimeLevel) {
            return TimeLevel.valueOf(name);
        } else if (input.getType() == IndicatorInputType.Integer) {
            return Integer.valueOf(constantsCache.get(name));
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
        } else if (input.getType() == IndicatorInputType.TimeLevel) {
            return TimeLevel.class;
        } else if (input.getType() == IndicatorInputType.Indicator) {
            return Indicator.class;
        }
        return null;
    }

}
