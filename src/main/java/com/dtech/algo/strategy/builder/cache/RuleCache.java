package com.dtech.algo.strategy.builder.cache;

import org.springframework.stereotype.Component;
import org.ta4j.core.Indicator;
import org.ta4j.core.Rule;

@Component
public class RuleCache extends ThreadLocalCache<String, Rule> {

}
