package com.dtech.algo.strategy.builder.cache;

import org.springframework.stereotype.Component;
import org.ta4j.core.Rule;

@Component
public class TradingRecordCache extends ThreadLocalCache<String, TradingRecordCache> {

}
