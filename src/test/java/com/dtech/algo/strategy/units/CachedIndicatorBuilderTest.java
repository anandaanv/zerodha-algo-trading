package com.dtech.algo.strategy.units;

import static org.junit.jupiter.api.Assertions.*;

import com.dtech.algo.exception.StrategyException;
import com.dtech.algo.indicators.IndicatorInfo;
import com.dtech.algo.indicators.IndicatorRegistry;
import com.dtech.algo.series.ExtendedBarSeries;
import com.dtech.algo.strategy.builder.cache.BarSeriesCache;
import com.dtech.algo.strategy.builder.cache.ConstantsCache;
import com.dtech.algo.strategy.builder.cache.IndicatorCache;
import com.dtech.algo.strategy.builder.ifc.BarSeriesLoader;
import com.dtech.algo.strategy.config.IndicatorConfig;
import com.dtech.algo.strategy.config.IndicatorInput;
import com.dtech.algo.strategy.config.IndicatorInputType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;

@ExtendWith(MockitoExtension.class)
class CachedIndicatorBuilderTest {

  @Mock
  private IndicatorCache indicatorCache;

  @Mock
  private ConstantsCache constantsCache;

  @Mock
  private BarSeriesCache barSeriesCache;

  @Spy
  private IndicatorRegistry indicatorRegistry = new IndicatorRegistry();

  @InjectMocks
  private CachedIndicatorBuilder indicatorBuilder;

  private ObjectWriter objectMapper = new ObjectMapper().writerWithDefaultPrettyPrinter();

  @Test
  void getIndicatorConstant() throws StrategyException, JsonProcessingException {
    indicatorRegistry.initialize();
    Mockito.doReturn("10")
        .when(constantsCache).get("value");
    Mockito.doReturn(new ExtendedBarSeries())
        .when(barSeriesCache).get("basebar");
    IndicatorConfig config = new IndicatorConfig();
    config.setKey("constant1");
    config.setIndicatorName("constant-indicator");
    IndicatorInput input = new IndicatorInput();
    input.setName("value");
    input.setType(IndicatorInputType.Number);
    IndicatorInput barSeries = new IndicatorInput();
    barSeries.setName("basebar");
    barSeries.setType(IndicatorInputType.BarSeries);
    config.setInputs(Arrays.asList(barSeries, input));
    System.out.println(objectMapper.writeValueAsString(config));
    Indicator indicator = indicatorBuilder.getIndicator(config);
    Num value = (Num) indicator.getValue(0);
    assertEquals(value.doubleValue(), 10.0, 0.1);
  }

  @Test
  void getIndicatorSMA() throws StrategyException, JsonProcessingException {
    indicatorRegistry.initialize();
    String barCount = "bar-count";
    Mockito.doReturn("10")
        .when(constantsCache).get(barCount);
    BarSeries baseBarSeries = new BaseBarSeries();
    ZonedDateTime now = ZonedDateTime.now().minus(1, ChronoUnit.DAYS);
    for(int x = 0; x < 100; x++) {
      baseBarSeries.addBar(now.plus(x, ChronoUnit.MINUTES), x, x, x, x, x);
    }
    ExtendedBarSeries series = ExtendedBarSeries.builder()
        .delegate(baseBarSeries)
        .build();
    String closePrice1 = "close-price1";
    Mockito.doReturn(new ClosePriceIndicator(series))
        .when(indicatorCache).get(closePrice1);
    String smaIndicator1 = "sma-indicator-1";
    Mockito.doReturn(null)
        .when(indicatorCache).get(smaIndicator1);
    IndicatorConfig config = new IndicatorConfig();
    config.setKey(smaIndicator1);
    config.setIndicatorName("s-m-a-indicator");
    IndicatorInput closePriceInput = new IndicatorInput();
    closePriceInput.setName(closePrice1);
    closePriceInput.setType(IndicatorInputType.Indicator);
    IndicatorInput barCountInput = new IndicatorInput();
    barCountInput.setName(barCount);
    barCountInput.setType(IndicatorInputType.Integer);
    config.setInputs(Arrays.asList(closePriceInput, barCountInput));
    System.out.println(objectMapper.writeValueAsString(config));
    Indicator indicator = indicatorBuilder.getIndicator(config);
    Num value = (Num) indicator.getValue(10);
    assertEquals(value.doubleValue(), 5.5, 0.1);
  }

  @Test
  void getIndicatorKingsCandle() throws StrategyException, JsonProcessingException {
    indicatorRegistry.initialize();
    String barCount = "bar-count";
    String timeLevelDay = "DAY";
    Mockito.doReturn("10")
            .when(constantsCache).get(barCount);
    BarSeries baseBarSeries = new BaseBarSeries();
    ZonedDateTime now = ZonedDateTime.now().minus(1, ChronoUnit.DAYS);
    for(int x = 0; x < 100; x++) {
      baseBarSeries.addBar(now.plus(x, ChronoUnit.MINUTES), x, x, x, x, x);
    }
    ExtendedBarSeries series = ExtendedBarSeries.builder()
            .delegate(baseBarSeries)
            .build();
    String baseBar = "basebar";
    Mockito.doReturn(series)
            .when(barSeriesCache).get("basebar");
    String kingsCandle = "kings-candle-upside";
    IndicatorConfig config = new IndicatorConfig();
    config.setKey(kingsCandle);
    config.setIndicatorName("kings-candle-upside");
    IndicatorInput closePriceInput = new IndicatorInput();
    closePriceInput.setName(baseBar);
    closePriceInput.setType(IndicatorInputType.BarSeries);
    IndicatorInput timeLevelInput = new IndicatorInput();
    timeLevelInput.setName(timeLevelDay);
    timeLevelInput.setType(IndicatorInputType.TimeLevel);
    IndicatorInput barCountInput = new IndicatorInput();
    barCountInput.setName(barCount);
    barCountInput.setType(IndicatorInputType.Integer);
    config.setInputs(Arrays.asList(closePriceInput, timeLevelInput, barCountInput));
    System.out.println(objectMapper.writeValueAsString(config));
    Indicator indicator = indicatorBuilder.getIndicator(config);
    Num value = (Num) indicator.getValue(10);
    assertEquals(value.doubleValue(), 9.9, 0.1);
  }
}