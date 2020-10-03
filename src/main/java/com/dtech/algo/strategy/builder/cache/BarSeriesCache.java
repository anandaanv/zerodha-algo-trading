package com.dtech.algo.strategy.builder.cache;

import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;

@Component
public class BarSeriesCache extends ThreadLocalCache<String, BarSeries> {
}
