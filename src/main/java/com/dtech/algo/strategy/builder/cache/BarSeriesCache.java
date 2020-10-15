package com.dtech.algo.strategy.builder.cache;

import com.dtech.algo.series.IntervalBarSeries;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;

@Component
public class BarSeriesCache extends ThreadLocalCache<String, IntervalBarSeries> {
}
