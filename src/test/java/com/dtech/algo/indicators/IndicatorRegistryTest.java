package com.dtech.algo.indicators;

import static org.junit.jupiter.api.Assertions.*;

import com.dtech.algo.registry.common.ConstructorArgs;
import com.dtech.algo.strategy.helper.ComponentHelper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.ta4j.core.indicators.pivotpoints.TimeLevel;
import org.ta4j.core.indicators.range.OpeningRangeLow;

class IndicatorRegistryTest {

  private ObjectWriter objectMapper = new ObjectMapper().writerWithDefaultPrettyPrinter();

  private ComponentHelper componentHelper = new ComponentHelper(null, null, null, null);

  @Test
  void getIndicatorClass() {
    IndicatorRegistry registry = getIndicatorRegistry();
    Class indicatorClass = registry.getIndicatorClass("opening-range-low");
    assertEquals(OpeningRangeLow.class, indicatorClass);
  }

  @Test
  void getIndicatorInfo() throws JsonProcessingException {
    IndicatorRegistry registry = getIndicatorRegistry();
    IndicatorInfo registryIndicatorInfo = registry.getObjectInfo("opening-range-low");
    ConstructorArgs[] args = new ConstructorArgs[3];
    args[0] = new ConstructorArgs("bar-series", "args0", Collections.emptyList());
    args[1] = new ConstructorArgs("time-level", "args1",
        Arrays.stream(TimeLevel.values()).map(TimeLevel::name).collect(Collectors.toList()));
    args[2] = new ConstructorArgs("int", "args2", Collections.emptyList());
    List<ConstructorArgs> targs = Arrays.asList(args);
    IndicatorConstructor con = IndicatorConstructor.builder()
        .args(targs)
        .build();
    IndicatorInfo indicatorInfo = IndicatorInfo.builder()
        .constructors(Collections.singletonList(con))
        .name("opening-range-low").build();
    System.out.println(objectMapper.writeValueAsString(indicatorInfo));
    assertEquals(registryIndicatorInfo, indicatorInfo);
  }

  @Test
  void getIndicatorInfoSma() throws JsonProcessingException {
    IndicatorRegistry registry = getIndicatorRegistry();
    IndicatorInfo registryIndicatorInfo = registry.getObjectInfo("s-m-a-indicator");
    IndicatorInfo indicatorInfo = componentHelper.getConstantIndicatorInfo("indicator", "int", "s-m-a-indicator");
    System.out.println(objectMapper.writeValueAsString(indicatorInfo));
    assertEquals(registryIndicatorInfo, indicatorInfo);
  }

  @Test
  void getIndicatorInfoConstant() throws JsonProcessingException {
    IndicatorRegistry registry = getIndicatorRegistry();
    IndicatorInfo registryIndicatorInfo = registry.getObjectInfo("constant-indicator");
    IndicatorInfo indicatorInfo = componentHelper.getConstantIndicatorInfo("bar-series", "num", "constant-indicator");
    System.out.println(objectMapper.writeValueAsString(indicatorInfo));
    assertEquals(registryIndicatorInfo, indicatorInfo);
  }

  @NotNull
  private IndicatorRegistry getIndicatorRegistry() {
    IndicatorRegistry registry = new IndicatorRegistry();
    registry.initialize();
    return registry;
  }
}