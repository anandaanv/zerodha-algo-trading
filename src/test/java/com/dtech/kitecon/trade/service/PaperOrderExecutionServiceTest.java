package com.dtech.kitecon.trade.service;

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

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaperOrderExecutionServiceTest {

    @Mock
    private TradeOrderRepository tradeOrderRepository;

    @Mock
    private CapitalAllocationService capitalAllocationService;

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
}
