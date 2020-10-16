package com.dtech.algo.strategy.units;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.rules.RuleRegistry;
import com.dtech.algo.strategy.builder.cache.ConstantsCache;
import com.dtech.algo.strategy.builder.cache.IndicatorCache;
import com.dtech.algo.strategy.builder.cache.RuleCache;
import com.dtech.algo.strategy.builder.ifc.RuleBuilder;
import com.dtech.algo.strategy.config.FollowUpRuleConfig;
import com.dtech.algo.strategy.config.RuleConfig;
import com.dtech.algo.strategy.config.RuleInput;
import com.dtech.algo.strategy.config.RuleInputType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.ta4j.core.Indicator;
import org.ta4j.core.Rule;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.num.Num;
import org.ta4j.core.num.PrecisionNum;

import java.lang.reflect.Constructor;
import java.util.List;

@Component
@RequiredArgsConstructor
public class CachedRuleBuilder extends AbstractObjectBuilder<Rule, RuleConfig> implements RuleBuilder {

    private final IndicatorCache indicatorCache;
    private final RuleCache ruleCache;

    private final ConstantsCache constantsCache;

    private final RuleRegistry registry;

    @Override
    public Rule getRule(RuleConfig config) throws StrategyException {
        return getObjectInternal(config.getKey(), config, ruleCache);
    }

    protected Rule buildObject(RuleConfig config) throws StrategyException {
        String name = config.getRuleName();
        Class<? extends Rule> indicatorClass = registry.getRuleClass(name);
        List<RuleInput> inputs = config.getInputs();
        Object[] parameters = resolveParameters(inputs, this::resolveValue);
        Class[] classes = resolveClasses(inputs, this::resolveClass);
        Constructor<? extends Rule> constructor = null;
        try {
            constructor = indicatorClass.getConstructor(classes);
            Rule generatedRule = constructor.newInstance(parameters);
            if (config.getFollowUpRules() != null && !config.getFollowUpRules().isEmpty()) {
                for (FollowUpRuleConfig rule : config.getFollowUpRules()) {
                    generatedRule = addFollowUpRules(generatedRule, rule);
                }
            }
            return generatedRule;
        } catch (Exception e) {
            throw new StrategyException("error occurred while creating indicator", e);
        }
    }

    private Rule addFollowUpRules(Rule indicator, FollowUpRuleConfig rule) throws StrategyException {
        switch (rule.getFollowUpRuleType()) {
            case And:
                return indicator.and(getRule(rule.getFollowUpRule()));
            case Or:
                return indicator.or(getRule(rule.getFollowUpRule()));
            case Xor:
                return indicator.xor(getRule(rule.getFollowUpRule()));
            default:
                return indicator;
        }
    }

    private Object resolveValue(RuleInput input) {
        String name = input.getName();
        if (input.getType() == RuleInputType.Indicator) {
            return indicatorCache.get(name);
        } else if (input.getType() == RuleInputType.Rule) {
            return ruleCache.get(name);
        } else if (input.getType() == RuleInputType.Number) {
            Num value = PrecisionNum.valueOf(Integer.valueOf(constantsCache.get(name)));
            return value;
        }
        return null;
    }

    private Class resolveClass(RuleInput input) {
        if (input.getType() == RuleInputType.Number) {
            return Num.class;
        } else if (input.getType() == RuleInputType.Rule) {
            return Rule.class;
        } else if (input.getType() == RuleInputType.Indicator) {
            return Indicator.class;
        } else if (input.getType() == RuleInputType.TradingRecord) {
            return TradingRecord.class;
        }
        return null;
    }

}
