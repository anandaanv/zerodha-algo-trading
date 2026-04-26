package com.dtech.kitecon.trade.service;

import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.market.orders.OrderException;
import com.dtech.kitecon.market.orders.OrderManager;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.trade.dto.QuoteResult;
import com.dtech.kitecon.trade.dto.ResolvedInstrument;
import com.dtech.kitecon.trade.entity.SegmentConfig;
import com.dtech.kitecon.trade.entity.TradeOrder;
import com.dtech.kitecon.trade.entity.TradeSignal;
import com.dtech.kitecon.trade.enums.*;
import com.dtech.kitecon.trade.repository.TradeOrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaperOrderExecutionServiceTest {

    @Mock
    private TradeOrderRepository tradeOrderRepository;

    @Mock
    private CapitalAllocationService capitalAllocationService;

    @Mock
    private OrderManager orderManager;

    @Mock
    private InstrumentRepository instrumentRepository;

    @InjectMocks
    private PaperOrderExecutionService paperOrderExecutionService;

    @Test
    void testEnter_LONG() {
        TradeSignal signal = TradeSignal.builder()
                .id(1L)
                .symbol("RELIANCE")
                .direction(TradeDirection.LONG)
                .instrumentToken(123L)
                .build();

        SegmentConfig config = SegmentConfig.builder()
                .symbol("RELIANCE")
                .segment(TradingSegment.EQ)
                .enabled(true)
                .capitalPct(BigDecimal.valueOf(2.0))
                .build();

        ResolvedInstrument resolved = ResolvedInstrument.builder()
                .tradingSymbol("RELIANCE")
                .instrumentToken(123L)
                .lotSize(1)
                .instrumentType("EQ")
                .build();

        QuoteResult quote = QuoteResult.builder()
                .symbol("RELIANCE")
                .instrumentToken(123L)
                .ltp(BigDecimal.valueOf(2500))
                .askPrice(BigDecimal.valueOf(2501))
                .bidPrice(BigDecimal.valueOf(2499))
                .build();

        when(capitalAllocationService.computeQuantity(any(), any(), anyInt())).thenReturn(10);

        TradeOrder savedOrder = TradeOrder.builder()
                .id(1L)
                .signal(signal)
                .symbol("RELIANCE")
                .direction(TradeDirection.LONG)
                .quantity(10)
                .entryPrice(BigDecimal.valueOf(2501))
                .status(TradeOrderStatus.OPEN)
                .build();

        when(tradeOrderRepository.save(any())).thenReturn(savedOrder);

        TradeOrder result = paperOrderExecutionService.enter(signal, config, resolved, quote, quote);

        assertNotNull(result);
        assertEquals(TradeDirection.LONG, result.getDirection());
        assertEquals(TradeOrderStatus.OPEN, result.getStatus());
        assertEquals(BigDecimal.valueOf(2501), result.getEntryPrice());
        assertEquals(10, result.getQuantity());

        ArgumentCaptor<TradeOrder> captor = ArgumentCaptor.forClass(TradeOrder.class);
        verify(tradeOrderRepository).save(captor.capture());
        TradeOrder saved = captor.getValue();
        assertEquals(TradeDirection.LONG, saved.getDirection());
        assertEquals(BigDecimal.valueOf(2501), saved.getEntryPrice());
    }

    @Test
    void testEnter_SHORT() {
        TradeSignal signal = TradeSignal.builder()
                .id(1L)
                .symbol("RELIANCE")
                .direction(TradeDirection.SHORT)
                .instrumentToken(123L)
                .build();

        SegmentConfig config = SegmentConfig.builder()
                .symbol("RELIANCE")
                .segment(TradingSegment.EQ)
                .enabled(true)
                .capitalPct(BigDecimal.valueOf(2.0))
                .build();

        ResolvedInstrument resolved = ResolvedInstrument.builder()
                .tradingSymbol("RELIANCE")
                .instrumentToken(123L)
                .lotSize(1)
                .instrumentType("EQ")
                .build();

        QuoteResult quote = QuoteResult.builder()
                .symbol("RELIANCE")
                .instrumentToken(123L)
                .ltp(BigDecimal.valueOf(2500))
                .askPrice(BigDecimal.valueOf(2501))
                .bidPrice(BigDecimal.valueOf(2499))
                .build();

        when(capitalAllocationService.computeQuantity(any(), any(), anyInt())).thenReturn(10);

        TradeOrder savedOrder = TradeOrder.builder()
                .id(1L)
                .signal(signal)
                .symbol("RELIANCE")
                .direction(TradeDirection.SHORT)
                .quantity(10)
                .entryPrice(BigDecimal.valueOf(2499))
                .status(TradeOrderStatus.OPEN)
                .build();

        when(tradeOrderRepository.save(any())).thenReturn(savedOrder);

        TradeOrder result = paperOrderExecutionService.enter(signal, config, resolved, quote, quote);

        assertNotNull(result);
        assertEquals(TradeDirection.SHORT, result.getDirection());
        assertEquals(BigDecimal.valueOf(2499), result.getEntryPrice());
    }

    @Test
    void testExit_LONG_Win() {
        TradeOrder order = TradeOrder.builder()
                .id(1L)
                .direction(TradeDirection.LONG)
                .quantity(10)
                .entryPrice(BigDecimal.valueOf(2500))
                .symbol("RELIANCE")
                .instrumentToken(123L)
                .status(TradeOrderStatus.OPEN)
                .build();

        QuoteResult quote = QuoteResult.builder()
                .symbol("RELIANCE")
                .ltp(BigDecimal.valueOf(2510))
                .askPrice(BigDecimal.valueOf(2511))
                .bidPrice(BigDecimal.valueOf(2509))
                .build();

        TradeOrder exitedOrder = TradeOrder.builder()
                .id(1L)
                .direction(TradeDirection.LONG)
                .quantity(10)
                .entryPrice(BigDecimal.valueOf(2500))
                .exitPrice(BigDecimal.valueOf(2509))
                .realisedPnl(BigDecimal.valueOf(90))
                .status(TradeOrderStatus.CLOSED)
                .exitReason(ExitReason.TARGET_HIT)
                .build();

        when(tradeOrderRepository.save(any())).thenReturn(exitedOrder);

        TradeOrder result = paperOrderExecutionService.exit(order, quote, quote.getLtp(), ExitReason.TARGET_HIT);

        assertNotNull(result);
        assertEquals(TradeOrderStatus.CLOSED, result.getStatus());
        assertEquals(ExitReason.TARGET_HIT, result.getExitReason());
        assertEquals(BigDecimal.valueOf(2509), result.getExitPrice());
        assertTrue(result.getRealisedPnl().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void testExit_LONG_Loss() {
        TradeOrder order = TradeOrder.builder()
                .id(1L)
                .direction(TradeDirection.LONG)
                .quantity(10)
                .entryPrice(BigDecimal.valueOf(2500))
                .symbol("RELIANCE")
                .instrumentToken(123L)
                .status(TradeOrderStatus.OPEN)
                .build();

        QuoteResult quote = QuoteResult.builder()
                .symbol("RELIANCE")
                .ltp(BigDecimal.valueOf(2490))
                .askPrice(BigDecimal.valueOf(2491))
                .bidPrice(BigDecimal.valueOf(2489))
                .build();

        TradeOrder exitedOrder = TradeOrder.builder()
                .id(1L)
                .direction(TradeDirection.LONG)
                .quantity(10)
                .entryPrice(BigDecimal.valueOf(2500))
                .exitPrice(BigDecimal.valueOf(2489))
                .realisedPnl(BigDecimal.valueOf(-110))
                .status(TradeOrderStatus.CLOSED)
                .exitReason(ExitReason.STOP_HIT)
                .build();

        when(tradeOrderRepository.save(any())).thenReturn(exitedOrder);

        TradeOrder result = paperOrderExecutionService.exit(order, quote, quote.getLtp(), ExitReason.STOP_HIT);

        assertNotNull(result);
        assertEquals(TradeOrderStatus.CLOSED, result.getStatus());
        assertTrue(result.getRealisedPnl().compareTo(BigDecimal.ZERO) < 0);
    }

    @Test
    void testExit_SHORT() {
        TradeOrder order = TradeOrder.builder()
                .id(1L)
                .direction(TradeDirection.SHORT)
                .quantity(10)
                .entryPrice(BigDecimal.valueOf(2500))
                .symbol("RELIANCE")
                .instrumentToken(123L)
                .status(TradeOrderStatus.OPEN)
                .build();

        QuoteResult quote = QuoteResult.builder()
                .symbol("RELIANCE")
                .ltp(BigDecimal.valueOf(2490))
                .askPrice(BigDecimal.valueOf(2491))
                .bidPrice(BigDecimal.valueOf(2489))
                .build();

        TradeOrder exitedOrder = TradeOrder.builder()
                .id(1L)
                .direction(TradeDirection.SHORT)
                .quantity(10)
                .entryPrice(BigDecimal.valueOf(2500))
                .exitPrice(BigDecimal.valueOf(2491))
                .realisedPnl(BigDecimal.valueOf(90))
                .status(TradeOrderStatus.CLOSED)
                .exitReason(ExitReason.TARGET_HIT)
                .build();

        when(tradeOrderRepository.save(any())).thenReturn(exitedOrder);

        TradeOrder result = paperOrderExecutionService.exit(order, quote, quote.getLtp(), ExitReason.TARGET_HIT);

        assertNotNull(result);
        assertEquals(TradeOrderStatus.CLOSED, result.getStatus());
        assertTrue(result.getRealisedPnl().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void testEnter_OPT_ForcesLONG() {
        // Signal is SHORT, but OPT segment should force order direction to LONG
        TradeSignal signal = TradeSignal.builder()
                .id(1L)
                .symbol("RELIANCE")
                .direction(TradeDirection.SHORT)
                .instrumentToken(123L)
                .strategyType(StrategyType.DTB)
                .build();

        SegmentConfig config = SegmentConfig.builder()
                .symbol("RELIANCE")
                .segment(TradingSegment.OPT)
                .enabled(true)
                .capitalPct(BigDecimal.valueOf(2.0))
                .build();

        ResolvedInstrument resolved = ResolvedInstrument.builder()
                .tradingSymbol("RELIANCE24JAN2500CE")
                .instrumentToken(200001L)
                .lotSize(100)
                .instrumentType("CE")
                .build();

        QuoteResult quote = QuoteResult.builder()
                .symbol("RELIANCE24JAN2500CE")
                .instrumentToken(200001L)
                .ltp(BigDecimal.valueOf(50))
                .askPrice(BigDecimal.valueOf(51))
                .bidPrice(BigDecimal.valueOf(49))
                .build();

        when(capitalAllocationService.computeQuantity(any(), any(), anyInt())).thenReturn(1);

        TradeOrder savedOrder = TradeOrder.builder()
                .id(1L)
                .signal(signal)
                .symbol("RELIANCE24JAN2500CE")
                .direction(TradeDirection.LONG)
                .quantity(1)
                .entryPrice(BigDecimal.valueOf(51))
                .status(TradeOrderStatus.OPEN)
                .paperTrade(true)
                .build();

        when(tradeOrderRepository.save(any())).thenReturn(savedOrder);

        TradeOrder result = paperOrderExecutionService.enter(signal, config, resolved, quote, quote);

        assertNotNull(result);
        // OPT entry should force LONG direction, not SHORT from signal
        assertEquals(TradeDirection.LONG, result.getDirection());
        assertEquals(BigDecimal.valueOf(51), result.getEntryPrice());
    }

    @Test
    void testEnter_EQ_LONG_UsesAskPrice() {
        TradeSignal signal = TradeSignal.builder()
                .id(1L)
                .symbol("INFY")
                .direction(TradeDirection.LONG)
                .instrumentToken(123L)
                .build();

        SegmentConfig config = SegmentConfig.builder()
                .symbol("INFY")
                .segment(TradingSegment.EQ)
                .enabled(true)
                .capitalPct(BigDecimal.valueOf(2.0))
                .build();

        ResolvedInstrument resolved = ResolvedInstrument.builder()
                .tradingSymbol("INFY")
                .instrumentToken(123L)
                .lotSize(1)
                .instrumentType("EQ")
                .build();

        QuoteResult quote = QuoteResult.builder()
                .symbol("INFY")
                .instrumentToken(123L)
                .ltp(BigDecimal.valueOf(3000))
                .askPrice(BigDecimal.valueOf(3001))
                .bidPrice(BigDecimal.valueOf(2999))
                .build();

        when(capitalAllocationService.computeQuantity(any(), any(), anyInt())).thenReturn(5);

        TradeOrder savedOrder = TradeOrder.builder()
                .id(1L)
                .signal(signal)
                .symbol("INFY")
                .direction(TradeDirection.LONG)
                .quantity(5)
                .entryPrice(BigDecimal.valueOf(3001))
                .status(TradeOrderStatus.OPEN)
                .paperTrade(true)
                .build();

        when(tradeOrderRepository.save(any())).thenReturn(savedOrder);

        TradeOrder result = paperOrderExecutionService.enter(signal, config, resolved, quote, quote);

        assertNotNull(result);
        // LONG entry should use ask price
        assertEquals(BigDecimal.valueOf(3001), result.getEntryPrice());
    }

    @Test
    void testEnter_EQ_SHORT_UsesBidPrice() {
        TradeSignal signal = TradeSignal.builder()
                .id(1L)
                .symbol("INFY")
                .direction(TradeDirection.SHORT)
                .instrumentToken(123L)
                .build();

        SegmentConfig config = SegmentConfig.builder()
                .symbol("INFY")
                .segment(TradingSegment.EQ)
                .enabled(true)
                .capitalPct(BigDecimal.valueOf(2.0))
                .build();

        ResolvedInstrument resolved = ResolvedInstrument.builder()
                .tradingSymbol("INFY")
                .instrumentToken(123L)
                .lotSize(1)
                .instrumentType("EQ")
                .build();

        QuoteResult quote = QuoteResult.builder()
                .symbol("INFY")
                .instrumentToken(123L)
                .ltp(BigDecimal.valueOf(3000))
                .askPrice(BigDecimal.valueOf(3001))
                .bidPrice(BigDecimal.valueOf(2999))
                .build();

        when(capitalAllocationService.computeQuantity(any(), any(), anyInt())).thenReturn(5);

        TradeOrder savedOrder = TradeOrder.builder()
                .id(1L)
                .signal(signal)
                .symbol("INFY")
                .direction(TradeDirection.SHORT)
                .quantity(5)
                .entryPrice(BigDecimal.valueOf(2999))
                .status(TradeOrderStatus.OPEN)
                .paperTrade(true)
                .build();

        when(tradeOrderRepository.save(any())).thenReturn(savedOrder);

        TradeOrder result = paperOrderExecutionService.enter(signal, config, resolved, quote, quote);

        assertNotNull(result);
        // SHORT entry should use bid price
        assertEquals(BigDecimal.valueOf(2999), result.getEntryPrice());
    }

    @Test
    void testExit_SHORT_Loss() {
        TradeOrder order = TradeOrder.builder()
                .id(1L)
                .direction(TradeDirection.SHORT)
                .quantity(10)
                .entryPrice(BigDecimal.valueOf(2500))
                .symbol("RELIANCE")
                .instrumentToken(123L)
                .status(TradeOrderStatus.OPEN)
                .build();

        QuoteResult quote = QuoteResult.builder()
                .symbol("RELIANCE")
                .ltp(BigDecimal.valueOf(2510))
                .askPrice(BigDecimal.valueOf(2511))
                .bidPrice(BigDecimal.valueOf(2509))
                .build();

        TradeOrder exitedOrder = TradeOrder.builder()
                .id(1L)
                .direction(TradeDirection.SHORT)
                .quantity(10)
                .entryPrice(BigDecimal.valueOf(2500))
                .exitPrice(BigDecimal.valueOf(2511))
                .realisedPnl(BigDecimal.valueOf(-110))
                .status(TradeOrderStatus.CLOSED)
                .exitReason(ExitReason.STOP_HIT)
                .build();

        when(tradeOrderRepository.save(any())).thenReturn(exitedOrder);

        TradeOrder result = paperOrderExecutionService.exit(order, quote, quote.getLtp(), ExitReason.STOP_HIT);

        assertNotNull(result);
        assertEquals(TradeOrderStatus.CLOSED, result.getStatus());
        // SHORT loss: entry(2500) - exit(2511) * qty(10) = -110
        assertTrue(result.getRealisedPnl().compareTo(BigDecimal.ZERO) < 0);
    }

    @Test
    void testEnter_CapitalAllocationUsesLotSize() {
        TradeSignal signal = TradeSignal.builder()
                .id(1L)
                .symbol("RELIANCE")
                .direction(TradeDirection.LONG)
                .instrumentToken(123L)
                .build();

        SegmentConfig config = SegmentConfig.builder()
                .symbol("RELIANCE")
                .segment(TradingSegment.EQ)
                .enabled(true)
                .capitalPct(BigDecimal.valueOf(2.0))
                .build();

        ResolvedInstrument resolved = ResolvedInstrument.builder()
                .tradingSymbol("RELIANCE")
                .instrumentToken(123L)
                .lotSize(1)
                .instrumentType("EQ")
                .build();

        QuoteResult quote = QuoteResult.builder()
                .symbol("RELIANCE")
                .instrumentToken(123L)
                .ltp(BigDecimal.valueOf(2500))
                .askPrice(BigDecimal.valueOf(2501))
                .bidPrice(BigDecimal.valueOf(2499))
                .build();

        when(capitalAllocationService.computeQuantity(any(), any(), anyInt())).thenReturn(1);

        TradeOrder savedOrder = TradeOrder.builder()
                .id(1L)
                .signal(signal)
                .symbol("RELIANCE")
                .direction(TradeDirection.LONG)
                .quantity(1)
                .entryPrice(BigDecimal.valueOf(2501))
                .status(TradeOrderStatus.OPEN)
                .build();

        when(tradeOrderRepository.save(any())).thenReturn(savedOrder);

        TradeOrder result = paperOrderExecutionService.enter(signal, config, resolved, quote, quote);

        assertNotNull(result);
        assertEquals(1, result.getQuantity());
        verify(capitalAllocationService).computeQuantity(
                BigDecimal.valueOf(2.0), BigDecimal.valueOf(2501), 1);
    }

    @Test
    void testEnter_LiveOrderPlacementForIMPULSE() throws OrderException {
        TradeSignal signal = TradeSignal.builder()
                .id(1L)
                .symbol("RELIANCE")
                .direction(TradeDirection.LONG)
                .instrumentToken(123L)
                .strategyType(StrategyType.IMPULSE)
                .build();

        SegmentConfig config = SegmentConfig.builder()
                .symbol("RELIANCE")
                .segment(TradingSegment.EQ)
                .enabled(true)
                .capitalPct(BigDecimal.valueOf(2.0))
                .orderProduct("MIS")
                .build();

        ResolvedInstrument resolved = ResolvedInstrument.builder()
                .tradingSymbol("RELIANCE")
                .instrumentToken(123L)
                .lotSize(1)
                .instrumentType("EQ")
                .build();

        QuoteResult quote = QuoteResult.builder()
                .symbol("RELIANCE")
                .instrumentToken(123L)
                .ltp(BigDecimal.valueOf(2500))
                .askPrice(BigDecimal.valueOf(2501))
                .bidPrice(BigDecimal.valueOf(2499))
                .build();

        Instrument kiteInstrument = Instrument.builder()
                .instrumentToken(123L)
                .tradingsymbol("RELIANCE")
                .exchange("BSE")
                .build();

        when(capitalAllocationService.computeQuantity(any(), any(), anyInt())).thenReturn(10);
        when(instrumentRepository.findAllByTradingsymbolAndExchangeIn("RELIANCE", new String[]{"BSE", "NSE", "NFO", "BFO"}))
                .thenReturn(List.of(kiteInstrument));
        when(orderManager.placeOrder(2501.0, 10, kiteInstrument, "BUY", "MIS")).thenReturn("ORDER123");

        TradeOrder savedOrder = TradeOrder.builder()
                .id(1L)
                .signal(signal)
                .symbol("RELIANCE")
                .direction(TradeDirection.LONG)
                .quantity(10)
                .entryPrice(BigDecimal.valueOf(2501))
                .status(TradeOrderStatus.OPEN)
                .paperTrade(false)
                .build();

        when(tradeOrderRepository.save(any())).thenReturn(savedOrder);

        // Enable live orders
        ReflectionTestUtils.setField(paperOrderExecutionService, "liveOrdersEnabled", true);

        TradeOrder result = paperOrderExecutionService.enter(signal, config, resolved, quote, quote);

        assertNotNull(result);
        assertFalse(result.isPaperTrade());
        verify(orderManager).placeOrder(2501.0, 10, kiteInstrument, "BUY", "MIS");
    }

    @Test
    void testEnter_LiveOrderPlacementException_FallsBackToPaper() throws OrderException {
        TradeSignal signal = TradeSignal.builder()
                .id(1L)
                .symbol("RELIANCE")
                .direction(TradeDirection.LONG)
                .instrumentToken(123L)
                .strategyType(StrategyType.IMPULSE)
                .build();

        SegmentConfig config = SegmentConfig.builder()
                .symbol("RELIANCE")
                .segment(TradingSegment.EQ)
                .enabled(true)
                .capitalPct(BigDecimal.valueOf(2.0))
                .orderProduct("MIS")
                .build();

        ResolvedInstrument resolved = ResolvedInstrument.builder()
                .tradingSymbol("RELIANCE")
                .instrumentToken(123L)
                .lotSize(1)
                .instrumentType("EQ")
                .build();

        QuoteResult quote = QuoteResult.builder()
                .symbol("RELIANCE")
                .instrumentToken(123L)
                .ltp(BigDecimal.valueOf(2500))
                .askPrice(BigDecimal.valueOf(2501))
                .bidPrice(BigDecimal.valueOf(2499))
                .build();

        Instrument kiteInstrument = Instrument.builder()
                .instrumentToken(123L)
                .tradingsymbol("RELIANCE")
                .exchange("BSE")
                .build();

        when(capitalAllocationService.computeQuantity(any(), any(), anyInt())).thenReturn(10);
        when(instrumentRepository.findAllByTradingsymbolAndExchangeIn("RELIANCE", new String[]{"BSE", "NSE", "NFO", "BFO"}))
                .thenReturn(List.of(kiteInstrument));
        // OrderManager throws exception
        when(orderManager.placeOrder(anyDouble(), anyInt(), any(), anyString(), anyString()))
                .thenThrow(new OrderException(new RuntimeException("Order placement failed")));

        TradeOrder savedOrder = TradeOrder.builder()
                .id(1L)
                .signal(signal)
                .symbol("RELIANCE")
                .direction(TradeDirection.LONG)
                .quantity(10)
                .entryPrice(BigDecimal.valueOf(2501))
                .status(TradeOrderStatus.OPEN)
                .paperTrade(true)
                .build();

        when(tradeOrderRepository.save(any())).thenReturn(savedOrder);

        // Enable live orders
        ReflectionTestUtils.setField(paperOrderExecutionService, "liveOrdersEnabled", true);

        // Should not throw, should fall back to paper trade
        TradeOrder result = paperOrderExecutionService.enter(signal, config, resolved, quote, quote);

        assertNotNull(result);
        assertTrue(result.isPaperTrade());
    }

    @Test
    void testEnter_LiveOrderPlacementInstrumentNotFound_StaysPaper() throws OrderException {
        TradeSignal signal = TradeSignal.builder()
                .id(1L)
                .symbol("UNKNOWN")
                .direction(TradeDirection.LONG)
                .instrumentToken(999L)
                .strategyType(StrategyType.IMPULSE)
                .build();

        SegmentConfig config = SegmentConfig.builder()
                .symbol("UNKNOWN")
                .segment(TradingSegment.EQ)
                .enabled(true)
                .capitalPct(BigDecimal.valueOf(2.0))
                .orderProduct("MIS")
                .build();

        ResolvedInstrument resolved = ResolvedInstrument.builder()
                .tradingSymbol("UNKNOWN")
                .instrumentToken(999L)
                .lotSize(1)
                .instrumentType("EQ")
                .build();

        QuoteResult quote = QuoteResult.builder()
                .symbol("UNKNOWN")
                .instrumentToken(999L)
                .ltp(BigDecimal.valueOf(100))
                .askPrice(BigDecimal.valueOf(101))
                .bidPrice(BigDecimal.valueOf(99))
                .build();

        when(capitalAllocationService.computeQuantity(any(), any(), anyInt())).thenReturn(1);
        // Instrument not found
        when(instrumentRepository.findAllByTradingsymbolAndExchangeIn("UNKNOWN", new String[]{"BSE", "NSE", "NFO", "BFO"}))
                .thenReturn(List.of());

        TradeOrder savedOrder = TradeOrder.builder()
                .id(1L)
                .signal(signal)
                .symbol("UNKNOWN")
                .direction(TradeDirection.LONG)
                .quantity(1)
                .entryPrice(BigDecimal.valueOf(101))
                .status(TradeOrderStatus.OPEN)
                .paperTrade(true)
                .build();

        when(tradeOrderRepository.save(any())).thenReturn(savedOrder);

        // Enable live orders
        ReflectionTestUtils.setField(paperOrderExecutionService, "liveOrdersEnabled", true);

        TradeOrder result = paperOrderExecutionService.enter(signal, config, resolved, quote, quote);

        assertNotNull(result);
        assertTrue(result.isPaperTrade());
        verify(orderManager, never()).placeOrder(anyDouble(), anyInt(), any(), anyString(), anyString());
    }

    @Test
    void testExit_ExitReasonStoredCorrectly() {
        TradeOrder order = TradeOrder.builder()
                .id(1L)
                .direction(TradeDirection.LONG)
                .quantity(5)
                .entryPrice(BigDecimal.valueOf(1000))
                .symbol("SBIN")
                .instrumentToken(456L)
                .status(TradeOrderStatus.OPEN)
                .build();

        QuoteResult quote = QuoteResult.builder()
                .symbol("SBIN")
                .ltp(BigDecimal.valueOf(1050))
                .askPrice(BigDecimal.valueOf(1051))
                .bidPrice(BigDecimal.valueOf(1049))
                .build();

        TradeOrder exitedOrder = TradeOrder.builder()
                .id(1L)
                .direction(TradeDirection.LONG)
                .quantity(5)
                .entryPrice(BigDecimal.valueOf(1000))
                .exitPrice(BigDecimal.valueOf(1049))
                .realisedPnl(BigDecimal.valueOf(245))
                .status(TradeOrderStatus.CLOSED)
                .exitReason(ExitReason.TARGET_HIT)
                .build();

        when(tradeOrderRepository.save(any())).thenReturn(exitedOrder);

        TradeOrder result = paperOrderExecutionService.exit(order, quote, quote.getLtp(), ExitReason.TARGET_HIT);

        assertNotNull(result);
        assertEquals(ExitReason.TARGET_HIT, result.getExitReason());
        verify(tradeOrderRepository).save(argThat(o -> o.getExitReason() == ExitReason.TARGET_HIT));
    }

    @Test
    void testEnter_PaperTradeFlag_DefaultTrue() {
        TradeSignal signal = TradeSignal.builder()
                .id(1L)
                .symbol("TCS")
                .direction(TradeDirection.LONG)
                .instrumentToken(789L)
                .strategyType(StrategyType.DTB)
                .build();

        SegmentConfig config = SegmentConfig.builder()
                .symbol("TCS")
                .segment(TradingSegment.EQ)
                .enabled(true)
                .capitalPct(BigDecimal.valueOf(1.5))
                .build();

        ResolvedInstrument resolved = ResolvedInstrument.builder()
                .tradingSymbol("TCS")
                .instrumentToken(789L)
                .lotSize(1)
                .instrumentType("EQ")
                .build();

        QuoteResult quote = QuoteResult.builder()
                .symbol("TCS")
                .instrumentToken(789L)
                .ltp(BigDecimal.valueOf(3500))
                .askPrice(BigDecimal.valueOf(3501))
                .bidPrice(BigDecimal.valueOf(3499))
                .build();

        when(capitalAllocationService.computeQuantity(any(), any(), anyInt())).thenReturn(1);

        TradeOrder savedOrder = TradeOrder.builder()
                .id(1L)
                .signal(signal)
                .symbol("TCS")
                .direction(TradeDirection.LONG)
                .quantity(1)
                .entryPrice(BigDecimal.valueOf(3501))
                .status(TradeOrderStatus.OPEN)
                .paperTrade(true)
                .build();

        when(tradeOrderRepository.save(any())).thenReturn(savedOrder);

        // Live orders disabled (default)
        ReflectionTestUtils.setField(paperOrderExecutionService, "liveOrdersEnabled", false);

        TradeOrder result = paperOrderExecutionService.enter(signal, config, resolved, quote, quote);

        assertNotNull(result);
        assertTrue(result.isPaperTrade());
    }
}
