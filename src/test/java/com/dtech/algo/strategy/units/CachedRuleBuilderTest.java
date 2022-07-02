package com.dtech.algo.strategy.units;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.rules.RuleRegistry;
import com.dtech.algo.strategy.builder.cache.ConstantsCache;
import com.dtech.algo.strategy.builder.cache.IndicatorCache;
import com.dtech.algo.strategy.builder.cache.RuleCache;
import com.dtech.algo.strategy.config.FollowUpRuleConfig;
import com.dtech.algo.strategy.config.FollowUpRuleType;
import com.dtech.algo.strategy.config.RuleConfig;
import com.dtech.algo.strategy.config.RuleInput;
import com.dtech.algo.strategy.config.RuleInputType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
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
import org.ta4j.core.num.DecimalNum;

@ExtendWith(MockitoExtension.class)
class CachedRuleBuilderTest {

  @Mock
  private DojiIndicator dojiIndicator;

  @Mock
  private ClosePriceIndicator closePriceIndicator;

  @Mock
  private IndicatorCache indicatorCache;

  @Mock
  private RuleCache ruleCache;

  @Mock
  private ConstantsCache constantsCache;

  @Spy
  private RuleRegistry indicatorRegistry = new RuleRegistry();

  @InjectMocks
  private CachedRuleBuilder ruleBuilder;

  private ObjectWriter objectMapper = new ObjectMapper().writerWithDefaultPrettyPrinter();

  @BeforeEach
  public void before() {
    indicatorRegistry.initialise();
  }

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
    config.setInputs(Collections.singletonList(input));
    System.out.println(objectMapper.writeValueAsString(config));
    Rule rule = ruleBuilder.getRule(config);
    boolean value = rule.isSatisfied(10);
    assertTrue(value);
  }

  @Test
  void underIndicatorRule() throws JsonProcessingException, StrategyException {
    String closePriceIndicatorName = "close1";
    String closeOffset = "close-offset";
    Mockito.doReturn(this.closePriceIndicator)
        .when(indicatorCache).get(closePriceIndicatorName);
    Mockito.doReturn(DecimalNum.valueOf(10.0))
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
    boolean value = rule.isSatisfied(10);
    assertTrue(value);
  }

  @Test
  void followUpSingleRule() throws JsonProcessingException, StrategyException {
    String closePriceIndicatorName = "close1";
    String number12 = "number-12";
    String number8 = "number-8";
    Mockito.doReturn(this.closePriceIndicator)
        .when(indicatorCache).get(closePriceIndicatorName);
    Mockito.doReturn(DecimalNum.valueOf(10.0))
        .when(closePriceIndicator).getValue(10);
    Mockito.doReturn("12")
        .when(constantsCache).get(number12);
    Mockito.doReturn("8")
        .when(constantsCache).get(number8);

    // close price indicator
    RuleInput closePrice = new RuleInput();
    closePrice.setName("close1");
    closePrice.setType(RuleInputType.Indicator);

    // under indicator rule
    RuleConfig underIndicatorConfig = new RuleConfig();
    underIndicatorConfig.setKey(closePriceIndicatorName);
    underIndicatorConfig.setRuleName("under-indicator-rule");
    // constant 12
    RuleInput num12 = new RuleInput();
    num12.setName(number12);
    num12.setType(RuleInputType.Number);
    // build rule
    underIndicatorConfig.setInputs(Arrays.asList(closePrice, num12));

    // Over indicator Config
    RuleConfig overIndicatorConfig = new RuleConfig();
    overIndicatorConfig.setKey(closePriceIndicatorName);
    overIndicatorConfig.setRuleName("over-indicator-rule");
    // constant 8
    RuleInput num8 = new RuleInput();
    num8.setName(number8);
    num8.setType(RuleInputType.Number);
    // build rule
    overIndicatorConfig.setInputs(Arrays.asList(closePrice, num8));
    FollowUpRuleConfig followUpRule = FollowUpRuleConfig.builder()
        .followUpRule(overIndicatorConfig)
        .followUpRuleType(FollowUpRuleType.And)
        .build();
    underIndicatorConfig.setFollowUpRules(Collections.singletonList(followUpRule));

    //print rule
    System.out.println(objectMapper.writeValueAsString(underIndicatorConfig));

    Rule rule = ruleBuilder.getRule(underIndicatorConfig);
    boolean value = rule.isSatisfied(10);
    assertTrue(value);
  }
}