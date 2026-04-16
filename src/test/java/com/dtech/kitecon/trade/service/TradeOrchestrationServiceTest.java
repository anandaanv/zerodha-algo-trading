package com.dtech.kitecon.trade.service;

import com.dtech.kitecon.trade.dto.QuoteResult;
import com.dtech.kitecon.trade.dto.ResolvedInstrument;
import com.dtech.kitecon.trade.entity.SegmentConfig;
import com.dtech.kitecon.trade.entity.TradeOrder;
import com.dtech.kitecon.trade.entity.TradeSignal;
import com.dtech.kitecon.trade.enums.*;
import com.dtech.kitecon.trade.repository.SegmentConfigRepository;
import com.dtech.kitecon.trade.repository.TradeOrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradeOrchestrationServiceTest {

    @Mock
    private SegmentConfigRepository segmentConfigRepository;

    @Mock
    private InstrumentResolverService instrumentResolverService;

    @Mock
    private MarketQuoteService marketQuoteService;

    @Mock
    private PaperOrderExecutionService paperOrderExecutionService;

    @Mock
    private TradeOrderRepository tradeOrderRepository;

    @InjectMocks
    private TradeOrchestrationService tradeOrchestrationService;

    @Test
    void testOnEntryTriggered_WithConfigs() {
        TradeSignal signal = TradeSignal.builder()
                .id(1L)
                .symbol("RELIANCE")
                .direction(TradeDirection.LONG)
                .instrumentToken(123L)
                .build();

        SegmentConfig eqConfig = SegmentConfig.builder()
                .id(1L)
                .symbol("RELIANCE")
                .segment(TradingSegment.EQ)
                .enabled(true)
                .capitalPct(BigDecimal.valueOf(2.0))
                .build();

        SegmentConfig futConfig = SegmentConfig.builder()
                .id(2L)
                .symbol("RELIANCE")
                .segment(TradingSegment.FUT)
                .enabled(true)
                .capitalPct(BigDecimal.valueOf(2.0))
                .build();

        List<SegmentConfig> configs = Arrays.asList(eqConfig, futConfig);

        QuoteResult underlyingQuote = QuoteResult.builder()
                .symbol("RELIANCE")
                .ltp(BigDecimal.valueOf(2500))
                .askPrice(BigDecimal.valueOf(2501))
                .bidPrice(BigDecimal.valueOf(2499))
                .build();

        ResolvedInstrument eqResolved = ResolvedInstrument.builder()
                .tradingSymbol("RELIANCE")
                .instrumentType("EQ")
                .lotSize(1)
                .build();

        ResolvedInstrument futResolved = ResolvedInstrument.builder()
                .tradingSymbol("RELIANCE24JAN")
                .instrumentType("FUT")
                .lotSize(75)
                .build();

        when(segmentConfigRepository.findBySymbolAndEnabledTrue("RELIANCE"))
                .thenReturn(configs);
        when(marketQuoteService.getQuote(anyString(), any()))
                .thenReturn(underlyingQuote);
        when(instrumentResolverService.resolve("RELIANCE", TradingSegment.EQ, TradeDirection.LONG, BigDecimal.valueOf(2500)))
                .thenReturn(eqResolved);
        when(instrumentResolverService.resolve("RELIANCE", TradingSegment.FUT, TradeDirection.LONG, BigDecimal.valueOf(2500)))
                .thenReturn(futResolved);

        tradeOrchestrationService.onEntryTriggered(signal);

        verify(paperOrderExecutionService, times(2)).enter(any(), any(), any(), any(), any());
    }

    @Test
    void testOnEntryTriggered_NoConfigs() {
        TradeSignal signal = TradeSignal.builder()
                .id(1L)
                .symbol("RELIANCE")
                .build();

        when(segmentConfigRepository.findBySymbolAndEnabledTrue("RELIANCE"))
                .thenReturn(Collections.emptyList());

        tradeOrchestrationService.onEntryTriggered(signal);

        verify(paperOrderExecutionService, never()).enter(any(), any(), any(), any(), any());
    }

    @Test
    void testOnExitTriggered() {
        TradeSignal signal = TradeSignal.builder()
                .id(1L)
                .symbol("RELIANCE")
                .build();

        TradeOrder order = TradeOrder.builder()
                .id(1L)
                .symbol("RELIANCE24JAN")
                .instrumentToken(456L)
                .entryPrice(BigDecimal.valueOf(2500))
                .direction(TradeDirection.LONG)
                .quantity(10)
                .status(TradeOrderStatus.OPEN)
                .build();

        QuoteResult quote = QuoteResult.builder()
                .symbol("RELIANCE24JAN")
                .ltp(BigDecimal.valueOf(2510))
                .askPrice(BigDecimal.valueOf(2511))
                .bidPrice(BigDecimal.valueOf(2509))
                .build();

        when(tradeOrderRepository.findBySignalAndStatus(signal, TradeOrderStatus.OPEN))
                .thenReturn(Arrays.asList(order));
        when(marketQuoteService.getQuote("RELIANCE24JAN", 456L))
                .thenReturn(quote);

        tradeOrchestrationService.onExitTriggered(signal, ExitReason.TARGET_HIT);

        verify(paperOrderExecutionService, times(1)).exit(eq(order), any(), any(), eq(ExitReason.TARGET_HIT));
    }

    @Test
    void testOnExitTriggered_QuoteUnavailable() {
        TradeSignal signal = TradeSignal.builder()
                .id(1L)
                .symbol("RELIANCE")
                .build();

        TradeOrder order = TradeOrder.builder()
                .id(1L)
                .symbol("RELIANCE24JAN")
                .instrumentToken(456L)
                .entryPrice(BigDecimal.valueOf(2500))
                .direction(TradeDirection.LONG)
                .quantity(10)
                .status(TradeOrderStatus.OPEN)
                .build();

        when(tradeOrderRepository.findBySignalAndStatus(signal, TradeOrderStatus.OPEN))
                .thenReturn(Arrays.asList(order));
        when(marketQuoteService.getQuote("RELIANCE24JAN", 456L))
                .thenReturn(null);

        tradeOrchestrationService.onExitTriggered(signal, ExitReason.STOP_HIT);

        verify(paperOrderExecutionService, never()).exit(any(), any(), any(), any());
    }
}
