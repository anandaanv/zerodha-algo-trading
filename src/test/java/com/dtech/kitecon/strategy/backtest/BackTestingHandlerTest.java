package com.dtech.kitecon.strategy.backtest;
import com.dtech.algo.series.Interval;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.data.StrategyParameters;
import com.dtech.kitecon.misc.StrategyEnvironment;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.repository.StrategyParametersRepository;
import com.dtech.kitecon.strategy.TradeDirection;
import com.dtech.kitecon.strategy.TradingStrategy;
import com.dtech.kitecon.strategy.builder.StrategyBuilder;
import com.dtech.kitecon.strategy.builder.StrategyConfig;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import com.dtech.kitecon.strategy.dataloader.InstrumentDataLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ta4j.core.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BackTestingHandlerTest {
  @Mock private InstrumentRepository instrumentRepository;
  @Mock private StrategyParametersRepository strategyParametersRepository;
  @Mock private InstrumentDataLoader instrumentDataLoader;
  @Mock private StrategyBuilder strategyBuilder;
  @InjectMocks private BackTestingHandler backTestingHandler;
  private Instrument testInstrument;
  private BarSeries testBarSeries;

  @BeforeEach
  void setUp() {
    testInstrument = Instrument.builder().instrumentToken(123L).tradingsymbol("HDFCBANK").name("HDFC Bank").exchange("NSE").build();
    testBarSeries = createTestBarSeries("HDFCBANK");
  }

  private BarSeries createTestBarSeries(String name) {
    BarSeries series = new BaseBarSeriesBuilder().withName(name).build();
    ZoneId ist = ZoneId.of("Asia/Kolkata");
    Instant t1 = ZonedDateTime.of(2024, 1, 1, 9, 15, 0, 0, ist).toInstant();
    Instant t2 = t1.plusSeconds(60 * 15);
    Instant t3 = t2.plusSeconds(60 * 15);
    series.addBar(BarsLoader.getBar(100, 102, 99, 101, 1000, t1));
    series.addBar(BarsLoader.getBar(101, 103, 100, 102, 1100, t2));
    series.addBar(BarsLoader.getBar(102, 104, 101, 103, 1200, t3));
    return series;
  }

  @Test void testExecute_withBuyDirection_returnsSummaryWithBuy() {
    String instrumentName = "HDFCBANK";
    when(instrumentRepository.findByTradingsymbolAndExchangeIn(instrumentName, new String[]{"NSE", "NFO"})).thenReturn(testInstrument);
    when(strategyParametersRepository.findByStrategyNameAndInstrumentNameAndEnvironment(anyString(), eq(instrumentName), eq(StrategyEnvironment.DEV))).thenReturn(Collections.emptyList());
    when(instrumentDataLoader.loadData(instrumentName, Interval.FifteenMinute)).thenReturn(Map.of(testInstrument, testBarSeries));
    TradingStrategy mockStrategy = createMockStrategy(TradeDirection.Buy);
    when(strategyBuilder.build(eq(testInstrument), anyMap(), any(StrategyConfig.class))).thenReturn(mockStrategy);
    BacktestSummary result = backTestingHandler.execute(instrumentName, strategyBuilder, Interval.FifteenMinute);
    assertNotNull(result);
    assertTrue(result.getSummary().containsKey("Buy"));
  }

  @Test void testExecute_withSellDirection_returnsSummaryWithSell() {
    String instrumentName = "HDFCBANK";
    when(instrumentRepository.findByTradingsymbolAndExchangeIn(instrumentName, new String[]{"NSE", "NFO"})).thenReturn(testInstrument);
    when(strategyParametersRepository.findByStrategyNameAndInstrumentNameAndEnvironment(anyString(), eq(instrumentName), eq(StrategyEnvironment.DEV))).thenReturn(Collections.emptyList());
    when(instrumentDataLoader.loadData(instrumentName, Interval.FifteenMinute)).thenReturn(Map.of(testInstrument, testBarSeries));
    TradingStrategy mockStrategy = createMockStrategy(TradeDirection.Sell);
    when(strategyBuilder.build(eq(testInstrument), anyMap(), any(StrategyConfig.class))).thenReturn(mockStrategy);
    BacktestSummary result = backTestingHandler.execute(instrumentName, strategyBuilder, Interval.FifteenMinute);
    assertNotNull(result);
    assertTrue(result.getSummary().containsKey("Sell"));
  }

  @Test void testExecute_withBothDirections_returnsBuyAndSell() {
    String instrumentName = "HDFCBANK";
    when(instrumentRepository.findByTradingsymbolAndExchangeIn(instrumentName, new String[]{"NSE", "NFO"})).thenReturn(testInstrument);
    when(strategyParametersRepository.findByStrategyNameAndInstrumentNameAndEnvironment(anyString(), eq(instrumentName), eq(StrategyEnvironment.DEV))).thenReturn(Collections.emptyList());
    when(instrumentDataLoader.loadData(instrumentName, Interval.FifteenMinute)).thenReturn(Map.of(testInstrument, testBarSeries));
    TradingStrategy mockStrategy = createMockStrategy(TradeDirection.Both);
    when(strategyBuilder.build(eq(testInstrument), anyMap(), any(StrategyConfig.class))).thenReturn(mockStrategy);
    BacktestSummary result = backTestingHandler.execute(instrumentName, strategyBuilder, Interval.FifteenMinute);
    assertNotNull(result);
    assertTrue(result.getSummary().containsKey("Buy"));
    assertTrue(result.getSummary().containsKey("Sell"));
  }

  @Test void testGetStrategyConfig_returnsConfig() {
    String instrumentName = "HDFCBANK";
    when(strategyBuilder.getName()).thenReturn("ImpulseStrategy");
    when(strategyParametersRepository.findByStrategyNameAndInstrumentNameAndEnvironment(anyString(), eq(instrumentName), eq(StrategyEnvironment.DEV))).thenReturn(Collections.emptyList());
    StrategyConfig config = backTestingHandler.getStrategyConfig(instrumentName, strategyBuilder, StrategyEnvironment.DEV);
    assertNotNull(config);
  }

  @Test void testRunBacktestOnTa4jStrategy_computesCriteria() {
    String instrumentName = "HDFCBANK";
    when(instrumentRepository.findByTradingsymbolAndExchangeIn(instrumentName, new String[]{"NSE", "NFO"})).thenReturn(testInstrument);
    when(strategyParametersRepository.findByStrategyNameAndInstrumentNameAndEnvironment(anyString(), eq(instrumentName), eq(StrategyEnvironment.DEV))).thenReturn(Collections.emptyList());
    when(instrumentDataLoader.loadData(instrumentName, Interval.FifteenMinute)).thenReturn(Map.of(testInstrument, testBarSeries));
    TradingStrategy mockStrategy = createMockStrategy(TradeDirection.Buy);
    when(strategyBuilder.build(eq(testInstrument), anyMap(), any(StrategyConfig.class))).thenReturn(mockStrategy);
    BacktestSummary result = backTestingHandler.execute(instrumentName, strategyBuilder, Interval.FifteenMinute);
    assertNotNull(result);
    assertNotNull(result.getResults());
  }

  @Test void testExecute_emptyBarSeries_throwsException() {
    String instrumentName = "HDFCBANK";
    BarSeries emptyBarSeries = new BaseBarSeriesBuilder().withName(instrumentName).build();
    when(instrumentRepository.findByTradingsymbolAndExchangeIn(instrumentName, new String[]{"NSE", "NFO"})).thenReturn(testInstrument);
    when(strategyParametersRepository.findByStrategyNameAndInstrumentNameAndEnvironment(anyString(), eq(instrumentName), eq(StrategyEnvironment.DEV))).thenReturn(Collections.emptyList());
    when(instrumentDataLoader.loadData(instrumentName, Interval.FifteenMinute)).thenReturn(Map.of(testInstrument, emptyBarSeries));
    TradingStrategy mockStrategy = createMockStrategy(TradeDirection.Buy);
    when(strategyBuilder.build(eq(testInstrument), anyMap(), any(StrategyConfig.class))).thenReturn(mockStrategy);
    assertThrows(Exception.class, () -> backTestingHandler.execute(instrumentName, strategyBuilder, Interval.FifteenMinute));
  }

  private TradingStrategy createMockStrategy(TradeDirection direction) {
    TradingStrategy strategy = mock(TradingStrategy.class);
    when(strategy.getTradeDirection()).thenReturn(direction);
    when(strategy.getBuyStrategy()).thenReturn(mock(Strategy.class));
    when(strategy.getSellStrategy()).thenReturn(mock(Strategy.class));
    return strategy;
  }
}
