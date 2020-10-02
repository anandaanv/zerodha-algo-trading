package com.dtech.algo.strategy.builder.cache;

import org.springframework.stereotype.Component;
import org.ta4j.core.Indicator;

@Component
public class IndicatorCache extends ThreadLocalCache<String, Indicator> {

}
