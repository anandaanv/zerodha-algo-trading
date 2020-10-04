package com.dtech.algo.indicators;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.ta4j.core.indicators.pivotpoints.TimeLevel;
import org.ta4j.core.indicators.range.OpeningRangeHigh;
import org.ta4j.core.indicators.range.OpeningRangeLow;

class IndicatorRegistryTest {

  private ObjectWriter objectMapper = new ObjectMapper().writerWithDefaultPrettyPrinter();

  @Test
  void getIndicatorClass() {
    IndicatorRegistry registry = new IndicatorRegistry();
    Class indicatorClass = registry.getIndicatorClass("opening-range-low");
    assertEquals(OpeningRangeLow.class, indicatorClass);
  }

  @Test
  void getIndicatorInfo() throws JsonProcessingException {
    IndicatorRegistry registry = new IndicatorRegistry();
    IndicatorInfo registryIndicatorInfo = registry.getIndicatorInfo("opening-range-low");
    ConstructorArgs args[] = new ConstructorArgs[3];
    args[0] = new ConstructorArgs("bar-series", "args0", null);
    args[1] = new ConstructorArgs("time-level", "args1",
        Arrays.stream(TimeLevel.values()).map(TimeLevel::name).collect(Collectors.toList()));
    args[2] = new ConstructorArgs("int", "args2", null);
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
    IndicatorRegistry registry = new IndicatorRegistry();
    IndicatorInfo registryIndicatorInfo = registry.getIndicatorInfo("s-m-a-indicator");
    ConstructorArgs args[] = new ConstructorArgs[2];
    args[0] = new ConstructorArgs("indicator", "args0", null);
    args[1] = new ConstructorArgs("int", "args1", null);
    List<ConstructorArgs> targs = Arrays.asList(args);
    IndicatorConstructor con = IndicatorConstructor.builder()
        .args(targs)
        .build();
    IndicatorInfo indicatorInfo = IndicatorInfo.builder()
        .constructors(Collections.singletonList(con))
        .name("s-m-a-indicator").build();
    System.out.println(objectMapper.writeValueAsString(indicatorInfo));
    assertEquals(registryIndicatorInfo, indicatorInfo);
  }

  @Test
  void getIndicatorInfoConstant() throws JsonProcessingException {
    IndicatorRegistry registry = new IndicatorRegistry();
    IndicatorInfo registryIndicatorInfo = registry.getIndicatorInfo("constant-indicator");
    ConstructorArgs args[] = new ConstructorArgs[2];
    args[0] = new ConstructorArgs("bar-series", "args0", null);
    args[1] = new ConstructorArgs("num", "args1", null);
    List<ConstructorArgs> targs = Arrays.asList(args);
    IndicatorConstructor con = IndicatorConstructor.builder()
        .args(targs)
        .build();
    IndicatorInfo indicatorInfo = IndicatorInfo.builder()
        .constructors(Collections.singletonList(con))
        .name("constant-indicator").build();
    System.out.println(objectMapper.writeValueAsString(indicatorInfo));
    assertEquals(registryIndicatorInfo, indicatorInfo);
  }
}