package com.dtech.algo.strategy.builder;
import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.strategy.TradeStrategy;
import com.dtech.algo.strategy.builder.cache.ConstantsCache;
import com.dtech.algo.strategy.builder.ifc.IndicatorBuilder;
import com.dtech.algo.strategy.builder.ifc.RuleBuilder;
import com.dtech.algo.strategy.config.IndicatorConfig;
import com.dtech.algo.strategy.config.RuleConfig;
import com.dtech.algo.strategy.config.StrategyConfig;
import com.dtech.kitecon.strategy.TradeDirection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.ta4j.core.Rule;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FinalStrategyBuilderTest {
  @Mock private ConstantsCache constantsCache;
  @Mock private IndicatorBuilder indicatorBuilder;
  @Mock private RuleBuilder ruleBuilder;
  @InjectMocks private FinalStrategyBuilder finalStrategyBuilder;
  private StrategyConfig testConfig;

  @BeforeEach
  void setUp() {
    testConfig = StrategyConfig.builder().strategyName("TestStrategy").direction(TradeDirection.Buy).constants(Map.of("param1", "value1")).indicators(List.of(IndicatorConfig.builder().key("SMA").indicatorName("SMA").build())).rules(List.of(RuleConfig.builder().key("CrossAbove").ruleName("CrossAbove").build())).entry(List.of("Rule1")).exit(List.of("Rule3")).build();
  }

  @Test void testBuildStrategy_loadsConstantsIndicatorsAndRules() throws StrategyException {
    Rule mockEntryRule = mock(Rule.class);
    when(ruleBuilder.getRule(any(RuleConfig.class))).thenReturn(mockEntryRule);
    TradeStrategy strategy = finalStrategyBuilder.buildStrategy(testConfig);
    assertNotNull(strategy);
    verify(constantsCache, times(1)).put(anyString(), anyString());
    verify(indicatorBuilder, times(1)).getIndicator(any(IndicatorConfig.class));
  }

  @Test void testBuildStrategy_combinesRulesWithAND() throws StrategyException {
    Rule rule1 = mock(Rule.class);
    Rule combinedAndRule = mock(Rule.class);
    when(ruleBuilder.getRule(any(RuleConfig.class))).thenReturn(rule1);
    when(rule1.and(rule1)).thenReturn(combinedAndRule);
    StrategyConfig andConfig = StrategyConfig.builder().strategyName("AndTestStrategy").direction(TradeDirection.Buy).constants(Map.of()).indicators(List.of()).rules(List.of()).entry(List.of("Rule1")).exit(List.of("Rule2")).build();
    TradeStrategy strategy = finalStrategyBuilder.buildStrategy(andConfig);
    assertNotNull(strategy);
  }

  @Test void testBuildStrategy_cachesIndicators() throws StrategyException {
    Rule mockRule = mock(Rule.class);
    when(ruleBuilder.getRule(any(RuleConfig.class))).thenReturn(mockRule);
    TradeStrategy strategy = finalStrategyBuilder.buildStrategy(testConfig);
    assertNotNull(strategy);
    verify(indicatorBuilder, times(testConfig.getIndicators().size())).getIndicator(any(IndicatorConfig.class));
  }

  @Test void testBuildStrategy_withMissingIndicator_throwsStrategyException() throws StrategyException {
    Rule mockRule = mock(Rule.class);
    when(ruleBuilder.getRule(any(RuleConfig.class))).thenReturn(mockRule);
    when(indicatorBuilder.getIndicator(any(IndicatorConfig.class))).thenThrow(new StrategyException("Indicator not found"));
    StrategyConfig configWithMissingIndicator = StrategyConfig.builder().strategyName("TestStrategy").direction(TradeDirection.Buy).constants(Map.of()).indicators(List.of(IndicatorConfig.builder().key("MISSING").indicatorName("MISSING").build())).rules(List.of()).entry(List.of("Rule1")).exit(List.of("Rule2")).build();
    assertThrows(StrategyException.class, () -> finalStrategyBuilder.buildStrategy(configWithMissingIndicator));
  }
}
