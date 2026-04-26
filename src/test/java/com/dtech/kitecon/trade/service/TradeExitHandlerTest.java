package com.dtech.kitecon.trade.service;

import com.dtech.kitecon.trade.entity.TradeActionLog;
import com.dtech.kitecon.trade.entity.TradeExecution;
import com.dtech.kitecon.trade.entity.TradeMonitorLog;
import com.dtech.kitecon.trade.entity.TradeSignal;
import com.dtech.kitecon.trade.enums.ExitReason;
import com.dtech.kitecon.trade.enums.MonitorAction;
import com.dtech.kitecon.trade.enums.StrategyType;
import com.dtech.kitecon.trade.enums.TradeDirection;
import com.dtech.kitecon.trade.enums.TradeStatus;
import com.dtech.kitecon.trade.repository.TradeExecutionRepository;
import com.dtech.kitecon.trade.repository.TradeMonitorLogRepository;
import com.dtech.kitecon.trade.repository.TradeSignalRepository;
import com.dtech.kitecon.trade.strategy.ExitDecision;
import com.dtech.kitecon.trade.strategy.ExitStrategy;
import com.dtech.kitecon.trade.strategy.ExitStrategyRouter;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Safety-net unit tests for TradeExitHandler.
 * Pins current behavior before refactoring.
 *
 * No Spring context — pure Mockito with @ExtendWith(MockitoExtension.class)
 */
@ExtendWith(MockitoExtension.class)
class TradeExitHandlerTest {

    private static final BigDecimal BROKERAGE_PER_TRADE = BigDecimal.valueOf(1500);

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
    private RlExitClient rlExitClient;

    @Mock
    private ExitStrategyRouter exitStrategyRouter;

    @Mock
    private TradeActionLogger tradeActionLogger;

    @InjectMocks
    private TradeExitHandler handler;

    private TradeSignal signal;
    private TradeExecution execution;

    @BeforeEach
    void setUp() {
        // Set dryRun to true by default (can be overridden in tests)
        ReflectionTestUtils.setField(handler, "dryRun", true);

        // Create a sample signal
        signal = new TradeSignal();
        signal.setId(1L);
        signal.setSymbol("HDFCBANK");
        signal.setInstrumentToken(100L);
        signal.setDirection(TradeDirection.LONG);
        signal.setStrategyType(StrategyType.DTB);
        signal.setInstrumentType("EQ");
        signal.setStopLoss(new BigDecimal("1500.00"));
        signal.setTarget(new BigDecimal("1700.00"));
        signal.setStatus(TradeStatus.ACTIVE);

        // Create a sample execution
        execution = new TradeExecution();
        execution.setId(1L);
        execution.setSignal(signal);
        execution.setEntryPriceActual(new BigDecimal("1600.00"));
        execution.setNotionalValue(new BigDecimal("160000.00")); // 100 shares * 1600
        execution.setMarginDeployed(new BigDecimal("32000.00")); // 20% margin
    }

    // ── Test 1: SL hit for LONG position (LTP <= stopLoss) → exit triggered ──
    @Test
    void testStopLossHitForLongPosition() {
        BigDecimal ltpBelowSl = new BigDecimal("1500.00"); // LTP == SL
        when(brokerOrderService.fetchLtp("HDFCBANK", 100L)).thenReturn(ltpBelowSl);
        when(executionRepository.findBySignal(signal)).thenReturn(Optional.of(execution));

        handler.handle(signal, true);

        // Verify exit was triggered
        ArgumentCaptor<TradeExecution> execCaptor = ArgumentCaptor.forClass(TradeExecution.class);
        verify(executionRepository).save(execCaptor.capture());
        assertEquals(ExitReason.STOP_HIT, execCaptor.getValue().getExitReason());
        assertEquals(ltpBelowSl, execCaptor.getValue().getExitPriceActual());

        // Verify signal marked COMPLETED
        ArgumentCaptor<TradeSignal> signalCaptor = ArgumentCaptor.forClass(TradeSignal.class);
        verify(signalRepository).save(signalCaptor.capture());
        assertEquals(TradeStatus.COMPLETED, signalCaptor.getValue().getStatus());

        // Verify exit order created (dry-run format)
        assertTrue(execCaptor.getValue().getExitOrderId().contains("DRY-RUN-EXIT"));

        verify(tradeOrchestrationService).onExitTriggered(signal, ExitReason.STOP_HIT);
    }

    // ── Test 2: SL hit for SHORT position (LTP >= stopLoss) → exit triggered ──
    @Test
    void testStopLossHitForShortPosition() {
        signal.setDirection(TradeDirection.SHORT);
        signal.setStopLoss(new BigDecimal("1700.00"));
        signal.setTarget(new BigDecimal("1500.00"));

        BigDecimal ltpAboveSl = new BigDecimal("1700.00"); // LTP == SL for SHORT
        when(brokerOrderService.fetchLtp("HDFCBANK", 100L)).thenReturn(ltpAboveSl);
        when(executionRepository.findBySignal(signal)).thenReturn(Optional.of(execution));

        handler.handle(signal, true);

        // Verify exit triggered with STOP_HIT reason
        ArgumentCaptor<TradeExecution> execCaptor = ArgumentCaptor.forClass(TradeExecution.class);
        verify(executionRepository).save(execCaptor.capture());
        assertEquals(ExitReason.STOP_HIT, execCaptor.getValue().getExitReason());

        verify(tradeOrchestrationService).onExitTriggered(signal, ExitReason.STOP_HIT);
    }

    // ── Test 3: Target hit for LONG (LTP >= target) → exit triggered ──
    @Test
    void testTargetHitForLongPosition() {
        BigDecimal ltpAtTarget = new BigDecimal("1700.00"); // LTP == target
        when(brokerOrderService.fetchLtp("HDFCBANK", 100L)).thenReturn(ltpAtTarget);
        when(executionRepository.findBySignal(signal)).thenReturn(Optional.of(execution));

        handler.handle(signal, true);

        // Verify exit triggered with TARGET_HIT reason
        ArgumentCaptor<TradeExecution> execCaptor = ArgumentCaptor.forClass(TradeExecution.class);
        verify(executionRepository).save(execCaptor.capture());
        assertEquals(ExitReason.TARGET_HIT, execCaptor.getValue().getExitReason());

        verify(tradeOrchestrationService).onExitTriggered(signal, ExitReason.TARGET_HIT);
    }

    // ── Test 4: Target hit for SHORT (LTP <= target) → exit triggered ──
    @Test
    void testTargetHitForShortPosition() {
        signal.setDirection(TradeDirection.SHORT);
        signal.setStopLoss(new BigDecimal("1700.00"));
        signal.setTarget(new BigDecimal("1500.00"));

        BigDecimal ltpAtTarget = new BigDecimal("1500.00"); // LTP == target for SHORT
        when(brokerOrderService.fetchLtp("HDFCBANK", 100L)).thenReturn(ltpAtTarget);
        when(executionRepository.findBySignal(signal)).thenReturn(Optional.of(execution));

        handler.handle(signal, true);

        // Verify exit triggered with TARGET_HIT reason
        ArgumentCaptor<TradeExecution> execCaptor = ArgumentCaptor.forClass(TradeExecution.class);
        verify(executionRepository).save(execCaptor.capture());
        assertEquals(ExitReason.TARGET_HIT, execCaptor.getValue().getExitReason());

        verify(tradeOrchestrationService).onExitTriggered(signal, ExitReason.TARGET_HIT);
    }

    // ── Test 5: HOLD — price between SL and target, no exit ──
    @Test
    void testHoldWhenPriceBetweenSlAndTarget() {
        BigDecimal ltpBetween = new BigDecimal("1600.00"); // Between SL(1500) and Target(1700)
        when(brokerOrderService.fetchLtp("HDFCBANK", 100L)).thenReturn(ltpBetween);
        when(executionRepository.findBySignal(signal)).thenReturn(Optional.of(execution));

        handler.handle(signal, true);

        // Verify exit was NOT triggered
        verify(tradeOrchestrationService, never()).onExitTriggered(any(), any());

        // Verify monitoring log written
        verify(logRepository).save(any(TradeMonitorLog.class));

        // Verify signal is still ACTIVE (not transitioned)
        verify(signalRepository, never()).save(argThat(s -> s.getStatus() == TradeStatus.COMPLETED));
    }

    // ── Test 6: LTP fetch failure — skip tick, no crash ──
    @Test
    void testLtpFetchFailureSkipsTick() {
        when(brokerOrderService.fetchLtp("HDFCBANK", 100L)).thenReturn(null);

        handler.handle(signal, true);

        // Verify no exit triggered
        verify(executionRepository, never()).save(any(TradeExecution.class));
        verify(signalRepository, never()).save(any(TradeSignal.class));

        // Verify monitor log created with NONE action
        ArgumentCaptor<TradeMonitorLog> logCaptor = ArgumentCaptor.forClass(TradeMonitorLog.class);
        verify(logRepository).save(logCaptor.capture());
        assertEquals(MonitorAction.NONE, logCaptor.getValue().getActionTaken());
        assertTrue(logCaptor.getValue().getActionDetail().contains("LTP unavailable"));
    }

    // ── Test 7: No execution found for signal → log ERROR, return ──
    @Test
    void testNoExecutionFoundLogsError() {
        BigDecimal ltp = new BigDecimal("1600.00");
        when(brokerOrderService.fetchLtp("HDFCBANK", 100L)).thenReturn(ltp);
        when(executionRepository.findBySignal(signal)).thenReturn(Optional.empty());

        handler.handle(signal, true);

        // Verify error log written
        ArgumentCaptor<TradeMonitorLog> logCaptor = ArgumentCaptor.forClass(TradeMonitorLog.class);
        verify(logRepository).save(logCaptor.capture());
        assertEquals(MonitorAction.ERROR, logCaptor.getValue().getActionTaken());
        assertTrue(logCaptor.getValue().getActionDetail().contains("No execution record found"));

        // Verify no exit triggered
        verify(tradeOrchestrationService, never()).onExitTriggered(any(), any());
    }

    // ── Test 8a: P&L calculation — LONG win ──
    @Test
    void testPnlCalculationLongWin() {
        BigDecimal exitPrice = new BigDecimal("1700.00"); // +100 per share
        when(brokerOrderService.fetchLtp("HDFCBANK", 100L)).thenReturn(exitPrice);
        when(executionRepository.findBySignal(signal)).thenReturn(Optional.of(execution));

        handler.handle(signal, true);

        ArgumentCaptor<TradeExecution> execCaptor = ArgumentCaptor.forClass(TradeExecution.class);
        verify(executionRepository).save(execCaptor.capture());
        TradeExecution result = execCaptor.getValue();

        // Expected: 100 shares * 100 = 10,000 gross
        BigDecimal expectedGrossA = new BigDecimal("10000.00");
        assertEquals(expectedGrossA, result.getGrossPnlInr());

        // Expected: 10,000 - 1,500 brokerage = 8,500 net
        BigDecimal expectedNetPnl = new BigDecimal("8500.00");
        assertEquals(expectedNetPnl, result.getNetPnlInr());

        // Expected: 8,500 / 32,000 * 100 = 26.56%
        BigDecimal expectedNetPct = new BigDecimal("26.56");
        assertTrue(result.getNetPnlPct().compareTo(expectedNetPct) >= 0); // Allow rounding
        assertTrue(result.getNetPnlPct().compareTo(expectedNetPct.add(BigDecimal.ONE)) <= 0);
    }

    // ── Test 8b: P&L calculation — LONG loss (hits SL) ──
    @Test
    void testPnlCalculationLongLoss() {
        BigDecimal exitPrice = new BigDecimal("1499.00"); // Below SL(1500), triggers stop loss
        when(brokerOrderService.fetchLtp("HDFCBANK", 100L)).thenReturn(exitPrice);
        when(executionRepository.findBySignal(signal)).thenReturn(Optional.of(execution));

        handler.handle(signal, true);

        ArgumentCaptor<TradeExecution> execCaptor = ArgumentCaptor.forClass(TradeExecution.class);
        verify(executionRepository).save(execCaptor.capture());
        TradeExecution result = execCaptor.getValue();

        // Expected: 100 shares * -101 = -10,100 gross
        BigDecimal expectedGross = new BigDecimal("-10100.00");
        assertEquals(expectedGross, result.getGrossPnlInr());

        // Expected: -10,100 - 1,500 brokerage = -11,600 net
        BigDecimal expectedNetPnl = new BigDecimal("-11600.00");
        assertEquals(expectedNetPnl, result.getNetPnlInr());

        // Expected: -11,600 / 32,000 * 100 = -36.25% (loss)
        assertTrue(result.getNetPnlPct().signum() < 0); // Negative P&L
    }

    // ── Test 8c: P&L calculation — SHORT win ──
    @Test
    void testPnlCalculationShortWin() {
        signal.setDirection(TradeDirection.SHORT);
        signal.setStopLoss(new BigDecimal("1700.00"));
        signal.setTarget(new BigDecimal("1500.00"));

        BigDecimal exitPrice = new BigDecimal("1500.00"); // Short hits target (at or below)
        when(brokerOrderService.fetchLtp("HDFCBANK", 100L)).thenReturn(exitPrice);
        when(executionRepository.findBySignal(signal)).thenReturn(Optional.of(execution));

        handler.handle(signal, true);

        ArgumentCaptor<TradeExecution> execCaptor = ArgumentCaptor.forClass(TradeExecution.class);
        verify(executionRepository).save(execCaptor.capture());
        TradeExecution result = execCaptor.getValue();

        // SHORT: move = (1500-1600)/1600 = -6.25% → negated = +6.25%
        // Gross = 160000 * 0.0625 = 10000
        BigDecimal expectedGross = new BigDecimal("10000.00");
        assertEquals(expectedGross, result.getGrossPnlInr());

        // Net = 10000 - 1500 = 8500
        BigDecimal expectedNetPnl = new BigDecimal("8500.00");
        assertEquals(expectedNetPnl, result.getNetPnlInr());

        assertTrue(result.getNetPnlPct().signum() > 0); // Positive P&L
    }

    // ── Test 8d: P&L calculation — SHORT loss ──
    @Test
    void testPnlCalculationShortLoss() {
        signal.setDirection(TradeDirection.SHORT);
        signal.setStopLoss(new BigDecimal("1700.00"));
        signal.setTarget(new BigDecimal("1500.00"));

        BigDecimal exitPrice = new BigDecimal("1700.00"); // Short exits higher = loss (SL hit)
        when(brokerOrderService.fetchLtp("HDFCBANK", 100L)).thenReturn(exitPrice);
        when(executionRepository.findBySignal(signal)).thenReturn(Optional.of(execution));

        handler.handle(signal, true);

        ArgumentCaptor<TradeExecution> execCaptor = ArgumentCaptor.forClass(TradeExecution.class);
        verify(executionRepository).save(execCaptor.capture());
        TradeExecution result = execCaptor.getValue();

        // SHORT: move = (1700-1600)/1600 = 6.25% → negated = -6.25%
        // Gross = 160000 * -0.0625 = -10000
        BigDecimal expectedGross = new BigDecimal("-10000.00");
        assertEquals(expectedGross, result.getGrossPnlInr());

        // Net = -10000 - 1500 = -11500
        BigDecimal expectedNetPnl = new BigDecimal("-11500.00");
        assertEquals(expectedNetPnl, result.getNetPnlInr());

        assertTrue(result.getNetPnlPct().signum() < 0); // Negative P&L
    }

    // ── Test 9: Dry-run exit — creates DRY-RUN order ID, sets signal COMPLETED ──
    @Test
    void testDryRunExitCreatesOrderIdAndMarksCompleted() {
        ReflectionTestUtils.setField(handler, "dryRun", true);
        BigDecimal exitPrice = new BigDecimal("1700.00");
        when(brokerOrderService.fetchLtp("HDFCBANK", 100L)).thenReturn(exitPrice);
        when(executionRepository.findBySignal(signal)).thenReturn(Optional.of(execution));

        handler.handle(signal, true);

        ArgumentCaptor<TradeExecution> execCaptor = ArgumentCaptor.forClass(TradeExecution.class);
        verify(executionRepository, atLeast(1)).save(execCaptor.capture());
        TradeExecution saved = execCaptor.getValue();

        // Verify DRY-RUN order ID
        assertTrue(saved.getExitOrderId().startsWith("DRY-RUN-EXIT-"));
        assertTrue(saved.getExitOrderId().contains("1")); // Signal ID

        // Verify exit time set
        assertNotNull(saved.getExitTime());

        // Verify signal marked COMPLETED
        ArgumentCaptor<TradeSignal> signalCaptor = ArgumentCaptor.forClass(TradeSignal.class);
        verify(signalRepository).save(signalCaptor.capture());
        assertEquals(TradeStatus.COMPLETED, signalCaptor.getValue().getStatus());
    }

    // ── Test 10: ExitStrategy for IMPULSE type — delegates to ExitStrategyRouter ──
    @Test
    void testImpulseExitStrategyDelegation() {
        signal.setStrategyType(StrategyType.IMPULSE);
        signal.setStochRsiK(new BigDecimal("70.00"));
        signal.setRrRatio(new BigDecimal("2.00"));

        BigDecimal ltp = new BigDecimal("1650.00");
        when(brokerOrderService.fetchLtp("HDFCBANK", 100L)).thenReturn(ltp);
        when(executionRepository.findBySignal(signal)).thenReturn(Optional.of(execution));

        // Mock strategy returns EXIT decision
        ExitStrategy mockStrategy = mock(ExitStrategy.class);
        when(exitStrategyRouter.getStrategy(StrategyType.IMPULSE)).thenReturn(mockStrategy);
        when(mockStrategy.evaluate(signal, ltp, ltp))
                .thenReturn(ExitDecision.exit(ExitReason.TARGET_HIT, ltp));

        handler.handle(signal, true);

        // Verify strategy was consulted
        verify(exitStrategyRouter).getStrategy(StrategyType.IMPULSE);
        verify(mockStrategy).evaluate(signal, ltp, ltp);

        // Verify exit was triggered
        verify(tradeOrchestrationService).onExitTriggered(signal, ExitReason.TARGET_HIT);
    }

    // ── Test 11: IMPULSE UPDATE_SLAB action advances slab and logs ──
    @Test
    void testImpulseUpdateSlabAction() {
        signal.setStrategyType(StrategyType.IMPULSE);
        signal.setStopLoss(new BigDecimal("1500.00"));

        BigDecimal ltp = new BigDecimal("1650.00");
        when(brokerOrderService.fetchLtp("HDFCBANK", 100L)).thenReturn(ltp);
        when(executionRepository.findBySignal(signal)).thenReturn(Optional.of(execution));

        ExitStrategy mockStrategy = mock(ExitStrategy.class);
        when(exitStrategyRouter.getStrategy(StrategyType.IMPULSE)).thenReturn(mockStrategy);
        when(mockStrategy.evaluate(signal, ltp, ltp))
                .thenReturn(ExitDecision.updateSlab(1));

        handler.handle(signal, true);

        // Verify slab was logged
        verify(tradeActionLogger).logSlab(eq(signal), eq(1), eq(ltp), any(BigDecimal.class));

        // Verify signal was saved (slab update)
        verify(signalRepository).save(signal);

        // Verify monitoring log written
        verify(logRepository).save(any(TradeMonitorLog.class));

        // Verify NO exit triggered
        verify(tradeOrchestrationService, never()).onExitTriggered(any(), any());
    }

    // ── Test 12: IMPULSE HOLD action logs monitoring, no exit ──
    @Test
    void testImpulseHoldAction() {
        signal.setStrategyType(StrategyType.IMPULSE);

        BigDecimal ltp = new BigDecimal("1650.00");
        when(brokerOrderService.fetchLtp("HDFCBANK", 100L)).thenReturn(ltp);
        when(executionRepository.findBySignal(signal)).thenReturn(Optional.of(execution));

        ExitStrategy mockStrategy = mock(ExitStrategy.class);
        when(exitStrategyRouter.getStrategy(StrategyType.IMPULSE)).thenReturn(mockStrategy);
        when(mockStrategy.evaluate(signal, ltp, ltp))
                .thenReturn(ExitDecision.hold());

        handler.handle(signal, true);

        // Verify monitoring log written
        ArgumentCaptor<TradeMonitorLog> logCaptor = ArgumentCaptor.forClass(TradeMonitorLog.class);
        verify(logRepository).save(logCaptor.capture());
        assertEquals(MonitorAction.NONE, logCaptor.getValue().getActionTaken());
        assertTrue(logCaptor.getValue().getActionDetail().contains("Impulse monitoring"));

        // Verify NO exit triggered
        verify(tradeOrchestrationService, never()).onExitTriggered(any(), any());
    }

    // ── Test 13: Live mode exit places order, marks EXIT_PENDING ──
    @Test
    void testLiveExitPlacesOrderAndMarksPending() {
        ReflectionTestUtils.setField(handler, "dryRun", false);
        BigDecimal exitPrice = new BigDecimal("1700.00");
        when(brokerOrderService.fetchLtp("HDFCBANK", 100L)).thenReturn(exitPrice);
        when(executionRepository.findBySignal(signal)).thenReturn(Optional.of(execution));
        when(brokerOrderService.placeExitOrder(signal, execution)).thenReturn("ORD-12345");

        handler.handle(signal, false);

        // Verify order was placed
        verify(brokerOrderService).placeExitOrder(signal, execution);

        // Verify execution saved with order ID and reason
        ArgumentCaptor<TradeExecution> execCaptor = ArgumentCaptor.forClass(TradeExecution.class);
        verify(executionRepository).save(execCaptor.capture());
        assertEquals("ORD-12345", execCaptor.getValue().getExitOrderId());
        assertEquals(ExitReason.TARGET_HIT, execCaptor.getValue().getExitReason());

        // Verify signal marked EXIT_PENDING (not COMPLETED)
        ArgumentCaptor<TradeSignal> signalCaptor = ArgumentCaptor.forClass(TradeSignal.class);
        verify(signalRepository).save(signalCaptor.capture());
        assertEquals(TradeStatus.EXIT_PENDING, signalCaptor.getValue().getStatus());

        // Verify orchestration service NOT called (live mode)
        verify(tradeOrchestrationService, never()).onExitTriggered(any(), any());
    }

    // ── Test 14: checkFill in dry-run immediately completes signal ──
    @Test
    void testCheckFillInDryRunCompletesImmediately() {
        signal.setStatus(TradeStatus.EXIT_PENDING);
        when(executionRepository.findBySignal(signal)).thenReturn(Optional.of(execution));

        handler.checkFill(signal, true);

        // Verify signal transitioned to COMPLETED
        ArgumentCaptor<TradeSignal> signalCaptor = ArgumentCaptor.forClass(TradeSignal.class);
        verify(signalRepository).save(signalCaptor.capture());
        assertEquals(TradeStatus.COMPLETED, signalCaptor.getValue().getStatus());
    }

    // ── Test 15: Unrealised P&L calculation for LONG position ──
    @Test
    void testUnrealisedPnlForLongPosition() {
        BigDecimal ltp = new BigDecimal("1650.00"); // +50 profit per share
        when(brokerOrderService.fetchLtp("HDFCBANK", 100L)).thenReturn(ltp);
        when(executionRepository.findBySignal(signal)).thenReturn(Optional.of(execution));

        handler.handle(signal, true);

        ArgumentCaptor<TradeMonitorLog> logCaptor = ArgumentCaptor.forClass(TradeMonitorLog.class);
        verify(logRepository).save(logCaptor.capture());
        TradeMonitorLog log = logCaptor.getValue();

        // Verify unrealised P&L: 160000 * (1650-1600)/1600 = 160000 * 0.03125 = 5000
        BigDecimal expectedUnrealisedPnl = new BigDecimal("5000.00");
        assertEquals(expectedUnrealisedPnl, log.getUnrealisedPnlInr());
    }

    // ── Test 16: Unrealised P&L calculation for SHORT position ──
    @Test
    void testUnrealisedPnlForShortPosition() {
        signal.setDirection(TradeDirection.SHORT);
        signal.setStopLoss(new BigDecimal("1700.00"));
        signal.setTarget(new BigDecimal("1500.00"));

        BigDecimal ltp = new BigDecimal("1550.00"); // Short profit = exit lower
        when(brokerOrderService.fetchLtp("HDFCBANK", 100L)).thenReturn(ltp);
        when(executionRepository.findBySignal(signal)).thenReturn(Optional.of(execution));

        handler.handle(signal, true);

        ArgumentCaptor<TradeMonitorLog> logCaptor = ArgumentCaptor.forClass(TradeMonitorLog.class);
        verify(logRepository).save(logCaptor.capture());
        TradeMonitorLog log = logCaptor.getValue();

        // SHORT: move = (1550-1600)/1600 → negate → 3.125%
        // unrealised = 160000 * 0.03125 = 5000
        BigDecimal expectedUnrealisedPnl = new BigDecimal("5000.00");
        assertEquals(expectedUnrealisedPnl, log.getUnrealisedPnlInr());
    }

    // ── Test 17: Action logger called with correct exit action ──
    @Test
    void testActionLoggerCalledWithStopHit() {
        BigDecimal exitPrice = new BigDecimal("1500.00");
        when(brokerOrderService.fetchLtp("HDFCBANK", 100L)).thenReturn(exitPrice);
        when(executionRepository.findBySignal(signal)).thenReturn(Optional.of(execution));

        handler.handle(signal, true);

        ArgumentCaptor<TradeActionLog.TradeAction> actionCaptor =
                ArgumentCaptor.forClass(TradeActionLog.TradeAction.class);
        verify(tradeActionLogger).logWithPnl(eq(signal), actionCaptor.capture(), any(), any(), any());
        assertEquals(TradeActionLog.TradeAction.STOP_HIT, actionCaptor.getValue());
    }

    // ── Test 18: Action logger called with TARGET_HIT ──
    @Test
    void testActionLoggerCalledWithTargetHit() {
        BigDecimal exitPrice = new BigDecimal("1700.00");
        when(brokerOrderService.fetchLtp("HDFCBANK", 100L)).thenReturn(exitPrice);
        when(executionRepository.findBySignal(signal)).thenReturn(Optional.of(execution));

        handler.handle(signal, true);

        ArgumentCaptor<TradeActionLog.TradeAction> actionCaptor =
                ArgumentCaptor.forClass(TradeActionLog.TradeAction.class);
        verify(tradeActionLogger).logWithPnl(eq(signal), actionCaptor.capture(), any(), any(), any());
        assertEquals(TradeActionLog.TradeAction.TARGET_HIT, actionCaptor.getValue());
    }

    // ── Test 19: Monitor log includes unrealised P&L percentage ──
    @Test
    void testMonitorLogIncludesUnrealisedPnlPercentage() {
        BigDecimal ltp = new BigDecimal("1650.00");
        when(brokerOrderService.fetchLtp("HDFCBANK", 100L)).thenReturn(ltp);
        when(executionRepository.findBySignal(signal)).thenReturn(Optional.of(execution));

        handler.handle(signal, true);

        ArgumentCaptor<TradeMonitorLog> logCaptor = ArgumentCaptor.forClass(TradeMonitorLog.class);
        verify(logRepository).save(logCaptor.capture());
        TradeMonitorLog log = logCaptor.getValue();

        // Verify P&L% calculated: 5000 / 32000 * 100 = 15.625%
        assertNotNull(log.getUnrealisedPnlPct());
        assertTrue(log.getUnrealisedPnlPct().compareTo(BigDecimal.ZERO) > 0);
    }

    // ── Test 20: Null notional value doesn't crash P&L calculation ──
    @Test
    void testNullNotionalValueHandled() {
        execution.setNotionalValue(null);
        BigDecimal ltp = new BigDecimal("1650.00");
        when(brokerOrderService.fetchLtp("HDFCBANK", 100L)).thenReturn(ltp);
        when(executionRepository.findBySignal(signal)).thenReturn(Optional.of(execution));

        handler.handle(signal, true);

        // Verify monitor log written (no crash)
        ArgumentCaptor<TradeMonitorLog> logCaptor = ArgumentCaptor.forClass(TradeMonitorLog.class);
        verify(logRepository).save(logCaptor.capture());
        TradeMonitorLog log = logCaptor.getValue();

        // Unrealised P&L should be zero
        assertEquals(BigDecimal.ZERO, log.getUnrealisedPnlInr());
    }
}
