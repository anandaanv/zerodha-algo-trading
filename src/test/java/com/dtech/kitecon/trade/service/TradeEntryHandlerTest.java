package com.dtech.kitecon.trade.service;

import com.dtech.kitecon.patternscanner.PatternDto;
import com.dtech.kitecon.patternscanner.PatternScanService;
import com.dtech.kitecon.patternscanner.TradeFilterClient;
import com.dtech.kitecon.trade.entity.TradeActionLog;
import com.dtech.kitecon.trade.entity.TradeExecution;
import com.dtech.kitecon.trade.entity.TradeMonitorLog;
import com.dtech.kitecon.trade.entity.TradeSignal;
import com.dtech.kitecon.trade.enums.MonitorAction;
import com.dtech.kitecon.trade.enums.TradeDirection;
import com.dtech.kitecon.trade.enums.TradeStatus;
import com.dtech.kitecon.trade.repository.TradeExecutionRepository;
import com.dtech.kitecon.trade.repository.TradeMonitorLogRepository;
import com.dtech.kitecon.trade.repository.TradeSignalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Safety-net unit tests for TradeEntryHandler.
 * Pin current behavior before refactoring.
 * Pure Mockito tests — no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class TradeEntryHandlerTest {

    @Mock
    private BrokerOrderService brokerOrderService;

    @Mock
    private TradeSignalRepository signalRepository;

    @Mock
    private TradeExecutionRepository executionRepository;

    @Mock
    private TradeMonitorLogRepository logRepository;

    @Mock
    private TradeOrchestrationService tradeOrchestrationService;

    @Mock
    private PatternScanService patternScanService;

    @Mock
    private TradeFilterClient tradeFilterClient;

    @Mock
    private TradeActionLogger tradeActionLogger;

    @InjectMocks
    private TradeEntryHandler handler;

    @BeforeEach
    void setUp() {
        // Set @Value fields
        ReflectionTestUtils.setField(handler, "mlFilterThreshold", 0.82);
        ReflectionTestUtils.setField(handler, "notionalPerLot", 1000000L);
        ReflectionTestUtils.setField(handler, "marginPerLot", 300000L);
    }

    /**
     * Test 1: Entry triggered for LONG position when LTP >= entry price
     * Dry-run mode: execution created with DRY-RUN order ID, status = ACTIVE
     */
    @Test
    void testEntryTriggeredLong() {
        // Arrange
        TradeSignal signal = createSignal(TradeDirection.LONG, "100.00", "99.00", "101.00");
        signal.setId(1L);
        BigDecimal ltp = new BigDecimal("100.50");

        when(brokerOrderService.fetchLtp(anyString(), anyLong())).thenReturn(ltp);
        PatternDto patternDto = createPatternDto();
        when(patternScanService.computeCurrentIndicators(signal)).thenReturn(patternDto);
        when(tradeFilterClient.score(eq(patternDto), eq("TEST_PATTERN"), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(0.85); // Above threshold

        // Act
        handler.handle(signal, true); // dryRun = true

        // Assert
        ArgumentCaptor<TradeExecution> executionCaptor = ArgumentCaptor.forClass(TradeExecution.class);
        verify(executionRepository).save(executionCaptor.capture());
        TradeExecution execution = executionCaptor.getValue();
        assertEquals("DRY-RUN-ENTRY-1", execution.getEntryOrderId());
        assertEquals(ltp, execution.getEntryPriceActual());

        ArgumentCaptor<TradeSignal> signalCaptor = ArgumentCaptor.forClass(TradeSignal.class);
        verify(signalRepository).save(signalCaptor.capture()); // Only once for ACTIVE (ML score set but not saved)
        assertEquals(TradeStatus.ACTIVE, signalCaptor.getValue().getStatus());
    }

    /**
     * Test 2: Entry triggered for SHORT position when LTP <= entry price
     */
    @Test
    void testEntryTriggeredShort() {
        // Arrange
        TradeSignal signal = createSignal(TradeDirection.SHORT, "100.00", "101.00", "99.00");
        signal.setId(2L);
        BigDecimal ltp = new BigDecimal("99.50");

        when(brokerOrderService.fetchLtp(anyString(), anyLong())).thenReturn(ltp);
        PatternDto patternDto2 = createPatternDto();
        when(patternScanService.computeCurrentIndicators(signal)).thenReturn(patternDto2);
        when(tradeFilterClient.score(eq(patternDto2), eq("TEST_PATTERN"), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(0.90); // Above threshold

        // Act
        handler.handle(signal, true); // dryRun = true

        // Assert
        ArgumentCaptor<TradeExecution> executionCaptor = ArgumentCaptor.forClass(TradeExecution.class);
        verify(executionRepository).save(executionCaptor.capture());
        TradeExecution execution = executionCaptor.getValue();
        assertEquals("DRY-RUN-ENTRY-2", execution.getEntryOrderId());
    }

    /**
     * Test 3: Entry NOT triggered for LONG when LTP < entry price
     */
    @Test
    void testEntryNotTriggeredLongPriceBelowEntry() {
        // Arrange
        TradeSignal signal = createSignal(TradeDirection.LONG, "100.00", "99.00", "101.00");
        signal.setId(3L);
        BigDecimal ltp = new BigDecimal("99.50"); // Below entry

        when(brokerOrderService.fetchLtp(anyString(), anyLong())).thenReturn(ltp);

        // Act
        handler.handle(signal, true);

        // Assert
        verify(executionRepository, never()).save(any(TradeExecution.class));
        verify(signalRepository, never()).save(signal);

        ArgumentCaptor<TradeMonitorLog> logCaptor = ArgumentCaptor.forClass(TradeMonitorLog.class);
        verify(logRepository).save(logCaptor.capture());
        TradeMonitorLog log = logCaptor.getValue();
        assertEquals(MonitorAction.NONE, log.getActionTaken());
        assertTrue(log.getActionDetail().contains("Watching entry"));
    }

    /**
     * Test 4: Entry NOT triggered for SHORT when LTP > entry price
     */
    @Test
    void testEntryNotTriggeredShortPriceAboveEntry() {
        // Arrange
        TradeSignal signal = createSignal(TradeDirection.SHORT, "100.00", "101.00", "99.00");
        signal.setId(4L);
        BigDecimal ltp = new BigDecimal("100.50"); // Above entry

        when(brokerOrderService.fetchLtp(anyString(), anyLong())).thenReturn(ltp);

        // Act
        handler.handle(signal, true);

        // Assert
        verify(executionRepository, never()).save(any(TradeExecution.class));
        verify(logRepository).save(any(TradeMonitorLog.class));
    }

    /**
     * Test 5: Entry window expired → signal status set to EXPIRED
     */
    @Test
    void testEntryExpiredWindowClosed() {
        // Arrange
        TradeSignal signal = createSignal(TradeDirection.LONG, "100.00", "99.00", "101.00");
        signal.setId(5L);
        signal.setEntryValidUntil(Instant.now().minusSeconds(3600)); // Expired 1 hour ago

        // Act
        handler.handle(signal, true);

        // Assert
        ArgumentCaptor<TradeSignal> signalCaptor = ArgumentCaptor.forClass(TradeSignal.class);
        verify(signalRepository).save(signalCaptor.capture());
        assertEquals(TradeStatus.EXPIRED, signalCaptor.getValue().getStatus());

        ArgumentCaptor<TradeMonitorLog> logCaptor = ArgumentCaptor.forClass(TradeMonitorLog.class);
        verify(logRepository).save(logCaptor.capture());
        assertEquals(MonitorAction.SIGNAL_EXPIRED, logCaptor.getValue().getActionTaken());

        verify(brokerOrderService, never()).fetchLtp(anyString(), any());
    }

    /**
     * Test 6: Entry triggered but ML filter rejects (score < threshold)
     */
    @Test
    void testMlFilterRejectsEntry() {
        // Arrange
        TradeSignal signal = createSignal(TradeDirection.LONG, "100.00", "99.00", "101.00");
        signal.setId(6L);
        BigDecimal ltp = new BigDecimal("100.50");

        when(brokerOrderService.fetchLtp(anyString(), anyLong())).thenReturn(ltp);
        PatternDto patternDto6 = createPatternDto();
        when(patternScanService.computeCurrentIndicators(signal)).thenReturn(patternDto6);
        when(tradeFilterClient.score(eq(patternDto6), eq("TEST_PATTERN"), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(0.75); // Below threshold (0.82)

        // Act
        handler.handle(signal, true);

        // Assert
        ArgumentCaptor<TradeSignal> signalCaptor = ArgumentCaptor.forClass(TradeSignal.class);
        verify(signalRepository).save(signalCaptor.capture());
        assertEquals(TradeStatus.EXPIRED, signalCaptor.getValue().getStatus());

        ArgumentCaptor<TradeMonitorLog> logCaptor = ArgumentCaptor.forClass(TradeMonitorLog.class);
        verify(logRepository).save(logCaptor.capture());
        assertTrue(logCaptor.getValue().getActionDetail().contains("ML filter rejected"));

        verify(executionRepository, never()).save(any(TradeExecution.class));
    }

    /**
     * Test 7: Entry triggered with ML score exactly at threshold → entry proceeds
     */
    @Test
    void testMlFilterThresholdExactlyMet() {
        // Arrange
        TradeSignal signal = createSignal(TradeDirection.LONG, "100.00", "99.00", "101.00");
        signal.setId(7L);
        BigDecimal ltp = new BigDecimal("100.50");

        when(brokerOrderService.fetchLtp(anyString(), anyLong())).thenReturn(ltp);
        PatternDto patternDto7 = createPatternDto();
        when(patternScanService.computeCurrentIndicators(signal)).thenReturn(patternDto7);
        when(tradeFilterClient.score(eq(patternDto7), eq("TEST_PATTERN"), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(0.82); // Exactly at threshold

        // Act
        handler.handle(signal, true);

        // Assert
        ArgumentCaptor<TradeExecution> executionCaptor = ArgumentCaptor.forClass(TradeExecution.class);
        verify(executionRepository).save(executionCaptor.capture());
        assertNotNull(executionCaptor.getValue());

        ArgumentCaptor<TradeSignal> signalCaptor = ArgumentCaptor.forClass(TradeSignal.class);
        verify(signalRepository).save(signalCaptor.capture()); // Only once for ACTIVE (ML score set but not saved)
        assertEquals(TradeStatus.ACTIVE, signalCaptor.getValue().getStatus());
    }

    /**
     * Test 8: ML scoring exception → fail open (score = 0.9999, entry proceeds)
     */
    @Test
    void testMlScoringException() {
        // Arrange
        TradeSignal signal = createSignal(TradeDirection.LONG, "100.00", "99.00", "101.00");
        signal.setId(8L);
        BigDecimal ltp = new BigDecimal("100.50");

        when(brokerOrderService.fetchLtp(anyString(), anyLong())).thenReturn(ltp);
        when(patternScanService.computeCurrentIndicators(signal))
                .thenThrow(new RuntimeException("ML service unavailable"));

        // Act
        handler.handle(signal, true);

        // Assert — entry should proceed despite exception (fail open)
        ArgumentCaptor<TradeExecution> executionCaptor = ArgumentCaptor.forClass(TradeExecution.class);
        verify(executionRepository).save(executionCaptor.capture());
        assertNotNull(executionCaptor.getValue());

        ArgumentCaptor<TradeSignal> signalCaptor = ArgumentCaptor.forClass(TradeSignal.class);
        verify(signalRepository).save(signalCaptor.capture()); // Only once for ACTIVE (ML score set but not saved)
        assertEquals(TradeStatus.ACTIVE, signalCaptor.getValue().getStatus());
    }

    /**
     * Test 9: LTP fetch failure (null) → skip tick, no crash, no status change
     */
    @Test
    void testLtpFetchFailure() {
        // Arrange
        TradeSignal signal = createSignal(TradeDirection.LONG, "100.00", "99.00", "101.00");
        signal.setId(9L);

        when(brokerOrderService.fetchLtp(anyString(), anyLong())).thenReturn(null);

        // Act
        handler.handle(signal, true);

        // Assert
        verify(executionRepository, never()).save(any(TradeExecution.class));
        verify(signalRepository, never()).save(signal);

        ArgumentCaptor<TradeMonitorLog> logCaptor = ArgumentCaptor.forClass(TradeMonitorLog.class);
        verify(logRepository).save(logCaptor.capture());
        assertEquals(MonitorAction.NONE, logCaptor.getValue().getActionTaken());
        assertTrue(logCaptor.getValue().getActionDetail().contains("LTP unavailable"));
    }

    /**
     * Test 10: Dry-run mode creates execution with proper DRY-RUN order ID
     */
    @Test
    void testDryRunCreatesExecutionWithProperOrderId() {
        // Arrange
        TradeSignal signal = createSignal(TradeDirection.LONG, "100.00", "99.00", "101.00");
        signal.setId(10L);
        BigDecimal ltp = new BigDecimal("100.50");

        when(brokerOrderService.fetchLtp(anyString(), anyLong())).thenReturn(ltp);
        PatternDto patternDto10 = createPatternDto();
        when(patternScanService.computeCurrentIndicators(signal)).thenReturn(patternDto10);
        when(tradeFilterClient.score(eq(patternDto10), eq("TEST_PATTERN"), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(0.85);

        // Act
        handler.handle(signal, true); // dryRun = true

        // Assert
        ArgumentCaptor<TradeExecution> executionCaptor = ArgumentCaptor.forClass(TradeExecution.class);
        verify(executionRepository).save(executionCaptor.capture());
        TradeExecution execution = executionCaptor.getValue();
        assertEquals("DRY-RUN-ENTRY-10", execution.getEntryOrderId());
        assertEquals(signal, execution.getSignal());
        assertEquals(1, execution.getQuantity()); // lotSize defaults to 1
        assertEquals(new BigDecimal("1000000"), execution.getNotionalValue());
        assertEquals(new BigDecimal("300000"), execution.getMarginDeployed());
    }

    /**
     * Test 11: Live mode places market order, status set to ENTRY_PENDING
     */
    @Test
    void testLiveRunPlacesMarketOrder() {
        // Arrange
        TradeSignal signal = createSignal(TradeDirection.LONG, "100.00", "99.00", "101.00");
        signal.setId(11L);
        BigDecimal ltp = new BigDecimal("100.50");

        when(brokerOrderService.fetchLtp(anyString(), anyLong())).thenReturn(ltp);
        PatternDto patternDto11 = createPatternDto();
        when(patternScanService.computeCurrentIndicators(signal)).thenReturn(patternDto11);
        when(tradeFilterClient.score(eq(patternDto11), eq("TEST_PATTERN"), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(0.85);
        when(brokerOrderService.placeMarketOrder(signal)).thenReturn("ORDER-12345");

        // Act
        handler.handle(signal, false); // dryRun = false

        // Assert
        verify(brokerOrderService).placeMarketOrder(signal);
        verify(executionRepository, never()).save(any(TradeExecution.class)); // Not saved in live mode yet

        ArgumentCaptor<TradeSignal> signalCaptor = ArgumentCaptor.forClass(TradeSignal.class);
        verify(signalRepository).save(signalCaptor.capture()); // Only once for ENTRY_PENDING (ML score set but not saved)
        assertEquals(TradeStatus.ENTRY_PENDING, signalCaptor.getValue().getStatus());
        assertTrue(signalCaptor.getValue().getNotes().contains("entryOrder=ORDER-12345"));

        verify(tradeOrchestrationService, never()).onEntryTriggered(any());
    }

    /**
     * Test 12: TradeActionLog created on entry trigger
     */
    @Test
    void testTradeActionLogCreatedOnEntry() {
        // Arrange
        TradeSignal signal = createSignal(TradeDirection.LONG, "100.00", "99.00", "101.00");
        signal.setId(12L);
        BigDecimal ltp = new BigDecimal("100.50");

        when(brokerOrderService.fetchLtp(anyString(), anyLong())).thenReturn(ltp);
        PatternDto patternDto12 = createPatternDto();
        when(patternScanService.computeCurrentIndicators(signal)).thenReturn(patternDto12);
        when(tradeFilterClient.score(eq(patternDto12), eq("TEST_PATTERN"), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(0.85);

        // Act
        handler.handle(signal, true);

        // Assert
        verify(tradeActionLogger).log(signal, TradeActionLog.TradeAction.ENTRY_TRIGGERED, ltp,
                "Entry condition met");
        verify(tradeActionLogger).log(signal, TradeActionLog.TradeAction.ENTRY_FILLED, ltp, "Filled");
    }

    /**
     * Helper method to create a test signal
     */
    private TradeSignal createSignal(TradeDirection direction, String entryPrice, String stopLoss, String target) {
        return TradeSignal.builder()
                .symbol(direction == TradeDirection.LONG ? "HDFCBANK" : "RELIANCE")
                .instrumentToken(direction == TradeDirection.LONG ? 123L : 456L)
                .direction(direction)
                .entryPrice(new BigDecimal(entryPrice))
                .stopLoss(new BigDecimal(stopLoss))
                .target(new BigDecimal(target))
                .lotSize(1)
                .status(TradeStatus.WATCHING_ENTRY)
                .patternType("TEST_PATTERN")
                .signalTime(Instant.now())
                .candleTime(Instant.now())
                .build();
    }

    /**
     * Helper method to create a test PatternDto
     */
    private PatternDto createPatternDto() {
        return PatternDto.builder()
                .patternType("TEST_PATTERN")
                .bullish(true)
                .keyLevel(100.0)
                .target(105.0)
                .dailyRsi(55.0)
                .dailyAdx(25.0)
                .dailyAdxEma(24.0)
                .adxWatching(20.0)
                .adxWatchingEma(19.0)
                .adxConfirm(22.0)
                .adxConfirmEma(21.0)
                .macdWatching(0.5)
                .macdSignalWatching(0.4)
                .bbWidthWatching(2.0)
                .bbPctBWatching(0.5)
                .macdDaily(0.3)
                .macdSignalDaily(0.2)
                .bbWidthDaily(3.0)
                .bbPctBDaily(0.6)
                .bbExpanding(1.0)
                .bbAligned(1.0)
                .rsiSlope(0.5)
                .macdHistSlope(0.3)
                .adxSlope(0.2)
                .build();
    }
}
