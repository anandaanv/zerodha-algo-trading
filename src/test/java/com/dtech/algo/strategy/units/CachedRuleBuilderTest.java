package com.dtech.algo.strategy.units;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.rules.RuleRegistry;
import com.dtech.algo.strategy.builder.cache.ConstantsCache;
import com.dtech.algo.strategy.builder.cache.IndicatorCache;
import com.dtech.algo.strategy.builder.cache.RuleCache;
import com.dtech.algo.strategy.config.RuleConfig;
import com.dtech.algo.strategy.config.RuleInput;
import com.dtech.algo.strategy.config.RuleInputType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ta4j.core.Rule;
import org.ta4j.core.indicators.candles.DojiIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.PrecisionNum;

@ExtendWith(MockitoExtension.class)
class CachedRuleBuilderTest {

  @Mock
  private DojiIndicator dojiIndicator;

  @Mock
  private ClosePriceIndicator closePriceIndicator;

  @Mock
  private IndicatorCache indicatorCache;

  @Mock
  private ConstantsCache constantsCache;

  @Mock
  private RuleCache ruleCache;

  @Spy
  private RuleRegistry indicatorRegistry = new RuleRegistry();

  @InjectMocks
  private CachedRuleBuilder ruleBuilder;

  private ObjectWriter objectMapper = new ObjectMapper().writerWithDefaultPrettyPrinter();


  @Test
  void getBooleanRule() throws JsonProcessingException, StrategyException {
    Mockito.doReturn(Boolean.TRUE)
        .when(dojiIndicator).getValue(10);
    Mockito.doReturn(dojiIndicator)
        .when(indicatorCache).get("doji");
    RuleConfig config = new RuleConfig();
    config.setKey("doji1");
    config.setRuleName("boolean-indicator-rule");
    RuleInput input = new RuleInput();
    input.setName("doji");
    input.setType(RuleInputType.Indicator);
    config.setInputs(Arrays.asList(input));
    System.out.println(objectMapper.writeValueAsString(config));
    Rule rule = ruleBuilder.getRule(config);
    Boolean value = rule.isSatisfied(10);
    assertTrue(value);
  }

  @Test
  void getCrossedUpRule() throws JsonProcessingException, StrategyException {
    String closePriceIndicatorName = "close1";
    String closeOffset = "close-offset";
    Mockito.doReturn(this.closePriceIndicator)
        .when(indicatorCache).get(closePriceIndicatorName);
    Mockito.doReturn(PrecisionNum.valueOf(10.0))
        .when(closePriceIndicator).getValue(10);
    Mockito.doReturn("12")
        .when(constantsCache).get(closeOffset);
    RuleConfig config = new RuleConfig();
    config.setKey(closePriceIndicatorName);
    config.setRuleName("under-indicator-rule");
    RuleInput input = new RuleInput();
    input.setName("close1");
    input.setType(RuleInputType.Indicator);
    RuleInput num = new RuleInput();
    num.setName(closeOffset);
    num.setType(RuleInputType.Number);
    config.setInputs(Arrays.asList(input, num));
    System.out.println(objectMapper.writeValueAsString(config));
    Rule rule = ruleBuilder.getRule(config);
    Boolean value = rule.isSatisfied(10);
    assertTrue(value);
  }
}