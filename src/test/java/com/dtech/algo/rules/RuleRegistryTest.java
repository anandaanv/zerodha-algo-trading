package com.dtech.algo.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dtech.algo.registry.common.ConstructorArgs;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.ta4j.core.Rule;
import org.ta4j.core.trading.rules.AndRule;

class RuleRegistryTest {

  private ObjectWriter objectMapper = new ObjectMapper().writerWithDefaultPrettyPrinter();

  @Test
  void getRuleClass() {
    RuleRegistry registry = getRuleRegistry();
    Class<? extends Rule> indicatorClass = registry.getRuleClass("and-rule");
    assertEquals(AndRule.class, indicatorClass);
  }

  @NotNull
  private RuleRegistry getRuleRegistry() {
    RuleRegistry registry = new RuleRegistry();
    registry.initialise();
    return registry;
  }

  @Test
  void andRule() throws JsonProcessingException {
    RuleRegistry registry = getRuleRegistry();
    String ruleName = "and-rule";
    RuleInfo registryIndicatorInfo = registry.getObjectInfo(ruleName);
    ConstructorArgs[] args = new ConstructorArgs[2];
    args[0] = new ConstructorArgs("rule", "arg0", null);
    args[1] = new ConstructorArgs("rule", "arg1", null);
    List<ConstructorArgs> targs = Arrays.asList(args);
    RuleConstructor con = RuleConstructor.builder()
        .args(targs)
        .build();
    RuleInfo indicatorInfo = RuleInfo.builder()
        .constructors(Collections.singletonList(con))
        .name(ruleName).build();
    System.out.println(objectMapper.writeValueAsString(registryIndicatorInfo));
    assertEquals(registryIndicatorInfo, indicatorInfo);
  }

  @Test
  void booleanRule() throws JsonProcessingException {
    RuleRegistry registry = getRuleRegistry();
    String ruleName = "boolean-indicator-rule";
    RuleInfo registryIndicatorInfo = registry.getObjectInfo(ruleName);
    ConstructorArgs[] args = new ConstructorArgs[1];
    args[0] = new ConstructorArgs("indicator", "arg0", null);
    List<ConstructorArgs> targs = Arrays.asList(args);
    RuleConstructor con = RuleConstructor.builder()
        .args(targs)
        .build();
    RuleInfo indicatorInfo = RuleInfo.builder()
        .constructors(Collections.singletonList(con))
        .name(ruleName).build();
    System.out.println(objectMapper.writeValueAsString(registryIndicatorInfo));
    assertEquals(registryIndicatorInfo, indicatorInfo);
  }

}