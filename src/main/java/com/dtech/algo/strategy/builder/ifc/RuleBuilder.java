package com.dtech.algo.strategy.builder.ifc;

import com.dtech.algo.strategy.builder.cache.ConstantsCache;
import com.dtech.algo.strategy.config.RuleConfig;
import org.ta4j.core.Rule;

public interface RuleBuilder {
  Rule buildIndicator(RuleConfig config, ConstantsCache objectCache);
}
