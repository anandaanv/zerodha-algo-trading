package com.dtech.algo.strategy.builder.ifc;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.strategy.builder.cache.ConstantsCache;
import com.dtech.algo.strategy.config.RuleConfig;
import org.ta4j.core.Rule;

public interface RuleBuilder {
  Rule getRule(RuleConfig config) throws StrategyException;
}
