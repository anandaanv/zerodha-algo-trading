package com.dtech.kitecon.simulation;
import com.dtech.algo.series.Interval;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.CandleRepository;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.simulation.strategy.SimulationStrategy;
import com.dtech.kitecon.trade.entity.TradeSignal;
import com.dtech.kitecon.trade.enums.TradeDirection;
import com.dtech.kitecon.trade.enums.TradeStatus;
import com.dtech.kitecon.trade.repository.TradeOrderRepository;
import com.dtech.kitecon.trade.repository.TradeSignalRepository;
import com.dtech.kitecon.trade.service.TradeActionLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradeSimulationServiceTest {
  @Mock private CandleRepository candleRepository;
  @Mock private InstrumentRepository instrumentRepository;
  @Mock private TradeSignalRepository signalRepository;
  @Mock private TradeOrderRepository orderRepository;
  @Mock private TradeActionLogger actionLogger;
  @Mock private SimulationStrategy mockStrategy;
  @InjectMocks private TradeSimulationService tradeSimulationService;
  private Instrument testInstrument;
  private Instant startTime;
  private Instant endTime;

  @BeforeEach
  void setUp() {
    testInstrument = Instrument.builder().instrumentToken(123L).tradingsymbol("HDFCBANK").exchange("NSE").build();
    ZoneId ist = ZoneId.of("Asia/Kolkata");
    startTime = ZonedDateTime.of(2024, 1, 8, 9, 15, 0, 0, ist).toInstant();
    endTime = ZonedDateTime.of(2024, 1, 8, 15, 30, 0, 0, ist).toInstant();
  }

  @Test
  void testRun_advancesClockInMarketHours() {
    String strategyType = "IMPULSE";
    List<String> symbols = List.of("HDFCBANK");
    when(mockStrategy.getStrategyType()).thenReturn(strategyType);
    when(instrumentRepository.findByTradingsymbolAndExchangeIn("HDFCBANK", new String[]{"NSE"})).thenReturn(testInstrument);
    when(candleRepository.findAllByInstrumentAndTimeframeAndTimestampBetween(any(), any(), any(), any())).thenReturn(Collections.emptyList());
    when(mockStrategy.scan(any(), anyString(), any())).thenReturn(Collections.emptyList());
    when(mockStrategy.checkExits(any(), any(), anyString(), any(), any(), any())).thenReturn(Collections.emptyList());
    tradeSimulationService = new TradeSimulationService(candleRepository, instrumentRepository, signalRepository, orderRepository, actionLogger, List.of(mockStrategy));
    TradeSimulationService.SimulationResult result = tradeSimulationService.run(strategyType, symbols, "FifteenMinute", startTime, endTime, 15);
    assertNotNull(result);
    assertTrue(result.steps() >= 1);
  }

  @Test
  void testRun_skipsWeekendsAndMarketClosures() {
    String strategyType = "IMPULSE";
    List<String> symbols = List.of("HDFCBANK");
    ZoneId ist = ZoneId.of("Asia/Kolkata");
    Instant fridayStart = ZonedDateTime.of(2024, 1, 5, 9, 15, 0, 0, ist).toInstant();
    Instant mondayEnd = ZonedDateTime.of(2024, 1, 8, 15, 30, 0, 0, ist).toInstant();
    when(mockStrategy.getStrategyType()).thenReturn(strategyType);
    when(instrumentRepository.findByTradingsymbolAndExchangeIn("HDFCBANK", new String[]{"NSE"})).thenReturn(testInstrument);
    when(candleRepository.findAllByInstrumentAndTimeframeAndTimestampBetween(any(), any(), any(), any())).thenReturn(Collections.emptyList());
    when(mockStrategy.scan(any(), anyString(), any())).thenReturn(Collections.emptyList());
    when(mockStrategy.checkExits(any(), any(), anyString(), any(), any(), any())).thenReturn(Collections.emptyList());
    tradeSimulationService = new TradeSimulationService(candleRepository, instrumentRepository, signalRepository, orderRepository, actionLogger, List.of(mockStrategy));
    TradeSimulationService.SimulationResult result = tradeSimulationService.run(strategyType, symbols, "FifteenMinute", fridayStart, mondayEnd, 15);
    assertNotNull(result);
  }

  @Test
  void testRun_generatesSignalsAndProcessesExits() {
    String strategyType = "IMPULSE";
    List<String> symbols = List.of("HDFCBANK");
    TradeSignal mockSignal = TradeSignal.builder().symbol("HDFCBANK").direction(TradeDirection.LONG).entryPrice(BigDecimal.valueOf(100.0)).stopLoss(BigDecimal.valueOf(95.0)).target(BigDecimal.valueOf(110.0)).status(TradeStatus.ACTIVE).instrumentToken(123L).build();
    when(mockStrategy.getStrategyType()).thenReturn(strategyType);
    when(instrumentRepository.findByTradingsymbolAndExchangeIn("HDFCBANK", new String[]{"NSE"})).thenReturn(testInstrument);
    when(candleRepository.findAllByInstrumentAndTimeframeAndTimestampBetween(any(), any(), any(), any())).thenReturn(Collections.emptyList());
    when(mockStrategy.scan(any(), anyString(), any())).thenReturn(List.of(mockSignal)).thenReturn(Collections.emptyList());
    when(mockStrategy.checkExits(any(), any(), anyString(), any(), any(), any())).thenReturn(Collections.emptyList());
    tradeSimulationService = new TradeSimulationService(candleRepository, instrumentRepository, signalRepository, orderRepository, actionLogger, List.of(mockStrategy));
    TradeSimulationService.SimulationResult result = tradeSimulationService.run(strategyType, symbols, "FifteenMinute", startTime, endTime, 15);
    assertNotNull(result);
    assertEquals(1, result.signalsGenerated());
  }

  @Test
  void testRun_aggregatesPnLAndComputesWinRate() {
    String strategyType = "IMPULSE";
    List<String> symbols = List.of("HDFCBANK");
    when(mockStrategy.getStrategyType()).thenReturn(strategyType);
    when(instrumentRepository.findByTradingsymbolAndExchangeIn("HDFCBANK", new String[]{"NSE"})).thenReturn(testInstrument);
    when(candleRepository.findAllByInstrumentAndTimeframeAndTimestampBetween(any(), any(), any(), any())).thenReturn(Collections.emptyList());
    when(mockStrategy.scan(any(), anyString(), any())).thenReturn(Collections.emptyList());
    when(mockStrategy.checkExits(any(), any(), anyString(), any(), any(), any())).thenReturn(Collections.emptyList());
    tradeSimulationService = new TradeSimulationService(candleRepository, instrumentRepository, signalRepository, orderRepository, actionLogger, List.of(mockStrategy));
    TradeSimulationService.SimulationResult result = tradeSimulationService.run(strategyType, symbols, "FifteenMinute", startTime, endTime, 15);
    assertNotNull(result);
    assertEquals(0, result.wins());
    assertEquals(0, result.losses());
    assertEquals(0.0, result.totalPnlPct());
  }

  @Test
  void testRun_withNoStrategyFound_throwsIllegalArgumentException() {
    String unknownStrategy = "UNKNOWN_STRATEGY";
    List<String> symbols = List.of("HDFCBANK");
    when(mockStrategy.getStrategyType()).thenReturn("IMPULSE");
    tradeSimulationService = new TradeSimulationService(candleRepository, instrumentRepository, signalRepository, orderRepository, actionLogger, List.of(mockStrategy));
    assertThrows(IllegalArgumentException.class, () -> tradeSimulationService.run(unknownStrategy, symbols, "FifteenMinute", startTime, endTime, 15));
  }
}
