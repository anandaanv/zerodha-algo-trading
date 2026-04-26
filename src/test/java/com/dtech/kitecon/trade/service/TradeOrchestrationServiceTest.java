package com.dtech.kitecon.trade.service;

import com.dtech.kitecon.trade.dto.QuoteResult;
import com.dtech.kitecon.trade.dto.ResolvedInstrument;
import com.dtech.kitecon.trade.entity.SegmentConfig;
import com.dtech.kitecon.trade.entity.TradeActionLog;
import com.dtech.kitecon.trade.entity.TradeOrder;
import com.dtech.kitecon.trade.entity.TradeSignal;
import com.dtech.kitecon.trade.enums.*;
import com.dtech.kitecon.trade.repository.SegmentConfigRepository;
import com.dtech.kitecon.trade.repository.TradeOrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
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

    @Mock
    private TradeActionLogger tradeActionLogger;

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
        when(instrumentResolverService.resolve("RELIANCE", TradingSegment.EQ, TradeDirection.LONG, BigDecimal.valueOf(2500), 0.0))
                .thenReturn(eqResolved);
        when(instrumentResolverService.resolve("RELIANCE", TradingSegment.FUT, TradeDirection.LONG, BigDecimal.valueOf(2500), 0.0))
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

    // ============================================================================
    // NEW TESTS: onEntryTriggered Safety-Net Coverage
    // ============================================================================

    @Test
    void testOnEntryTriggered_MultipleSegmentConfigs_CreatesOrderForEach() {
        TradeSignal signal = TradeSignal.builder()
                .id(1L)
                .symbol("INFY")
                .direction(TradeDirection.LONG)
                .strategyType(StrategyType.DTB)
                .instrumentToken(789L)
                .build();

        SegmentConfig eqConfig = SegmentConfig.builder()
                .id(1L)
                .symbol("INFY")
                .segment(TradingSegment.EQ)
                .enabled(true)
                .capitalPct(BigDecimal.valueOf(1.0))
                .build();

        SegmentConfig ceConfig = SegmentConfig.builder()
                .id(2L)
                .symbol("INFY")
                .segment(TradingSegment.OPT)
                .enabled(true)
                .capitalPct(BigDecimal.valueOf(1.5))
                .build();

        SegmentConfig peConfig = SegmentConfig.builder()
                .id(3L)
                .symbol("INFY")
                .segment(TradingSegment.OPT)
                .enabled(false) // Disabled, should not be processed
                .capitalPct(BigDecimal.valueOf(1.5))
                .build();

        QuoteResult underlyingQuote = QuoteResult.builder()
                .symbol("INFY")
                .ltp(BigDecimal.valueOf(2200))
                .askPrice(BigDecimal.valueOf(2201))
                .bidPrice(BigDecimal.valueOf(2199))
                .build();

        ResolvedInstrument eqResolved = ResolvedInstrument.builder()
                .tradingSymbol("INFY")
                .instrumentToken(101L)
                .instrumentType("EQ")
                .lotSize(1)
                .build();

        ResolvedInstrument ceResolved = ResolvedInstrument.builder()
                .tradingSymbol("INFY2500CE")
                .instrumentToken(102L)
                .instrumentType("CE")
                .lotSize(600)
                .build();

        QuoteResult ceQuote = QuoteResult.builder()
                .symbol("INFY2500CE")
                .ltp(BigDecimal.valueOf(150))
                .askPrice(BigDecimal.valueOf(151))
                .bidPrice(BigDecimal.valueOf(149))
                .build();

        when(segmentConfigRepository.findBySymbolAndEnabledTrue("INFY"))
                .thenReturn(Arrays.asList(eqConfig, ceConfig));
        when(marketQuoteService.getQuote("INFY", 789L))
                .thenReturn(underlyingQuote);
        when(instrumentResolverService.resolve("INFY", TradingSegment.EQ, TradeDirection.LONG, BigDecimal.valueOf(2200), 0.0))
                .thenReturn(eqResolved);
        when(instrumentResolverService.resolve("INFY", TradingSegment.OPT, TradeDirection.LONG, BigDecimal.valueOf(2200), 0.0))
                .thenReturn(ceResolved);
        when(marketQuoteService.getQuote("INFY", 101L))
                .thenReturn(underlyingQuote);
        when(marketQuoteService.getQuote("INFY2500CE", 102L))
                .thenReturn(ceQuote);

        tradeOrchestrationService.onEntryTriggered(signal);

        // Should create 2 orders (EQ and CE), skip PE (disabled)
        verify(paperOrderExecutionService, times(2)).enter(any(), any(), any(), any(), any());
    }

    @Test
    void testOnEntryTriggered_UnderlyingQuoteUnavailable_SkipsSegmentContinuesToNext() {
        TradeSignal signal = TradeSignal.builder()
                .id(2L)
                .symbol("TCS")
                .direction(TradeDirection.SHORT)
                .strategyType(StrategyType.DTB)
                .instrumentToken(501L)
                .build();

        SegmentConfig eqConfig = SegmentConfig.builder()
                .id(4L)
                .symbol("TCS")
                .segment(TradingSegment.EQ)
                .enabled(true)
                .capitalPct(BigDecimal.valueOf(2.0))
                .build();

        SegmentConfig futConfig = SegmentConfig.builder()
                .id(5L)
                .symbol("TCS")
                .segment(TradingSegment.FUT)
                .enabled(true)
                .capitalPct(BigDecimal.valueOf(2.0))
                .build();

        QuoteResult validQuote = QuoteResult.builder()
                .symbol("TCS")
                .ltp(BigDecimal.valueOf(3500))
                .build();

        ResolvedInstrument futResolved = ResolvedInstrument.builder()
                .tradingSymbol("TCSFEB24")
                .instrumentToken(502L)
                .instrumentType("FUT")
                .lotSize(1)
                .build();

        // First call returns null (underlying for EQ), second call returns quote (underlying for FUT)
        when(marketQuoteService.getQuote("TCS", 501L))
                .thenReturn(null) // First segment underlying quote unavailable
                .thenReturn(validQuote); // Second segment underlying quote available

        when(segmentConfigRepository.findBySymbolAndEnabledTrue("TCS"))
                .thenReturn(Arrays.asList(eqConfig, futConfig));

        when(instrumentResolverService.resolve("TCS", TradingSegment.FUT, TradeDirection.SHORT, BigDecimal.valueOf(3500), 0.0))
                .thenReturn(futResolved);

        when(marketQuoteService.getQuote("TCSFEB24", 502L))
                .thenReturn(validQuote);

        tradeOrchestrationService.onEntryTriggered(signal);

        // Should skip EQ (no underlying quote) and still try FUT
        verify(paperOrderExecutionService, times(1)).enter(any(), any(), any(), any(), any());
    }

    @Test
    void testOnEntryTriggered_InstrumentResolutionException_CatchesContinuesToNext() {
        TradeSignal signal = TradeSignal.builder()
                .id(3L)
                .symbol("HDFC")
                .direction(TradeDirection.LONG)
                .strategyType(StrategyType.DTB)
                .instrumentToken(601L)
                .build();

        SegmentConfig eqConfig = SegmentConfig.builder()
                .id(6L)
                .symbol("HDFC")
                .segment(TradingSegment.EQ)
                .enabled(true)
                .build();

        SegmentConfig futConfig = SegmentConfig.builder()
                .id(7L)
                .symbol("HDFC")
                .segment(TradingSegment.FUT)
                .enabled(true)
                .build();

        QuoteResult quote = QuoteResult.builder()
                .symbol("HDFC")
                .ltp(BigDecimal.valueOf(2800))
                .build();

        ResolvedInstrument futResolved = ResolvedInstrument.builder()
                .tradingSymbol("HDFCFEB24")
                .instrumentToken(603L)
                .instrumentType("FUT")
                .build();

        QuoteResult futQuote = QuoteResult.builder()
                .symbol("HDFCFEB24")
                .ltp(BigDecimal.valueOf(2850))
                .build();

        when(segmentConfigRepository.findBySymbolAndEnabledTrue("HDFC"))
                .thenReturn(Arrays.asList(eqConfig, futConfig));
        when(marketQuoteService.getQuote("HDFC", 601L))
                .thenReturn(quote);

        // EQ resolution throws exception, FUT resolution succeeds
        when(instrumentResolverService.resolve("HDFC", TradingSegment.EQ, TradeDirection.LONG, BigDecimal.valueOf(2800), 0.0))
                .thenThrow(new RuntimeException("Instrument resolution failed for EQ"));

        when(instrumentResolverService.resolve("HDFC", TradingSegment.FUT, TradeDirection.LONG, BigDecimal.valueOf(2800), 0.0))
                .thenReturn(futResolved);

        when(marketQuoteService.getQuote("HDFCFEB24", 603L))
                .thenReturn(futQuote);

        tradeOrchestrationService.onEntryTriggered(signal);

        // Should skip EQ (exception) and continue to FUT
        verify(paperOrderExecutionService, times(1)).enter(any(), any(), any(), any(), any());
    }

    @Test
    void testOnEntryTriggered_InstrumentQuoteUnavailable_SkipsSegment() {
        TradeSignal signal = TradeSignal.builder()
                .id(4L)
                .symbol("WIPRO")
                .direction(TradeDirection.LONG)
                .strategyType(StrategyType.DTB)
                .instrumentToken(701L)
                .build();

        SegmentConfig ceConfig = SegmentConfig.builder()
                .id(8L)
                .symbol("WIPRO")
                .segment(TradingSegment.OPT)
                .enabled(true)
                .build();

        QuoteResult underlyingQuote = QuoteResult.builder()
                .symbol("WIPRO")
                .ltp(BigDecimal.valueOf(450))
                .build();

        ResolvedInstrument ceResolved = ResolvedInstrument.builder()
                .tradingSymbol("WIPRO500CE")
                .instrumentToken(702L)
                .instrumentType("CE")
                .build();

        when(segmentConfigRepository.findBySymbolAndEnabledTrue("WIPRO"))
                .thenReturn(Arrays.asList(ceConfig));
        when(marketQuoteService.getQuote("WIPRO", 701L))
                .thenReturn(underlyingQuote);
        when(instrumentResolverService.resolve("WIPRO", TradingSegment.OPT, TradeDirection.LONG, BigDecimal.valueOf(450), 0.0))
                .thenReturn(ceResolved);

        // Instrument quote not available
        when(marketQuoteService.getQuote("WIPRO500CE", 702L))
                .thenReturn(null);

        tradeOrchestrationService.onEntryTriggered(signal);

        // Should skip segment due to missing instrument quote
        verify(paperOrderExecutionService, never()).enter(any(), any(), any(), any(), any());
    }

    @Test
    void testOnEntryTriggered_PaperOrderEntryException_CatchesAndContinues() {
        TradeSignal signal = TradeSignal.builder()
                .id(5L)
                .symbol("BAJAJ")
                .direction(TradeDirection.SHORT)
                .strategyType(StrategyType.DTB)
                .instrumentToken(801L)
                .build();

        SegmentConfig eqConfig = SegmentConfig.builder()
                .id(9L)
                .symbol("BAJAJ")
                .segment(TradingSegment.EQ)
                .enabled(true)
                .build();

        SegmentConfig futConfig = SegmentConfig.builder()
                .id(10L)
                .symbol("BAJAJ")
                .segment(TradingSegment.FUT)
                .enabled(true)
                .build();

        QuoteResult quote = QuoteResult.builder()
                .symbol("BAJAJ")
                .ltp(BigDecimal.valueOf(1800))
                .build();

        ResolvedInstrument eqResolved = ResolvedInstrument.builder()
                .tradingSymbol("BAJAJ")
                .instrumentToken(802L)
                .instrumentType("EQ")
                .build();

        ResolvedInstrument futResolved = ResolvedInstrument.builder()
                .tradingSymbol("BAJAJFEB24")
                .instrumentToken(803L)
                .instrumentType("FUT")
                .build();

        when(segmentConfigRepository.findBySymbolAndEnabledTrue("BAJAJ"))
                .thenReturn(Arrays.asList(eqConfig, futConfig));
        when(marketQuoteService.getQuote("BAJAJ", 801L))
                .thenReturn(quote);
        when(instrumentResolverService.resolve("BAJAJ", TradingSegment.EQ, TradeDirection.SHORT, BigDecimal.valueOf(1800), 0.0))
                .thenReturn(eqResolved);
        when(instrumentResolverService.resolve("BAJAJ", TradingSegment.FUT, TradeDirection.SHORT, BigDecimal.valueOf(1800), 0.0))
                .thenReturn(futResolved);
        when(marketQuoteService.getQuote("BAJAJ", 802L))
                .thenReturn(quote);
        when(marketQuoteService.getQuote("BAJAJFEB24", 803L))
                .thenReturn(quote);

        // EQ order entry throws exception, FUT succeeds
        doThrow(new RuntimeException("Order placement failed"))
                .when(paperOrderExecutionService).enter(any(), eq(eqConfig), any(), any(), any());

        tradeOrchestrationService.onEntryTriggered(signal);

        // Should attempt both EQ and FUT despite EQ failure
        ArgumentCaptor<SegmentConfig> configCaptor = ArgumentCaptor.forClass(SegmentConfig.class);
        verify(paperOrderExecutionService, times(2)).enter(any(), configCaptor.capture(), any(), any(), any());
        // Verify that despite exception on EQ, FUT was still attempted
        assertTrue(configCaptor.getAllValues().stream().anyMatch(c -> c.getSegment() == TradingSegment.FUT),
                "FUT should still be attempted after EQ failure");
    }

    @Test
    void testOnEntryTriggered_ImpulseStrategyUsesOtmOffset() {
        TradeSignal impulseSignal = TradeSignal.builder()
                .id(6L)
                .symbol("SBIN")
                .direction(TradeDirection.LONG)
                .strategyType(StrategyType.IMPULSE)
                .instrumentToken(901L)
                .build();

        SegmentConfig ceConfig = SegmentConfig.builder()
                .id(11L)
                .symbol("SBIN")
                .segment(TradingSegment.OPT)
                .enabled(true)
                .build();

        QuoteResult quote = QuoteResult.builder()
                .symbol("SBIN")
                .ltp(BigDecimal.valueOf(600))
                .build();

        ResolvedInstrument ceResolved = ResolvedInstrument.builder()
                .tradingSymbol("SBIN618CE")
                .instrumentToken(902L)
                .instrumentType("CE")
                .build();

        QuoteResult ceQuote = QuoteResult.builder()
                .symbol("SBIN618CE")
                .ltp(BigDecimal.valueOf(50))
                .build();

        when(segmentConfigRepository.findBySymbolAndEnabledTrue("SBIN"))
                .thenReturn(Arrays.asList(ceConfig));
        when(marketQuoteService.getQuote("SBIN", 901L))
                .thenReturn(quote);
        when(marketQuoteService.getQuote("SBIN618CE", 902L))
                .thenReturn(ceQuote);

        ArgumentCaptor<Double> otmCaptor = ArgumentCaptor.forClass(Double.class);
        when(instrumentResolverService.resolve(eq("SBIN"), eq(TradingSegment.OPT), eq(TradeDirection.LONG),
                eq(BigDecimal.valueOf(600)), otmCaptor.capture()))
                .thenReturn(ceResolved);

        tradeOrchestrationService.onEntryTriggered(impulseSignal);

        // Verify OTM offset of 0.03 for IMPULSE strategy
        assertEquals(0.03, otmCaptor.getValue(), "IMPULSE strategy should use 0.03 OTM offset");
        verify(paperOrderExecutionService, times(1)).enter(any(), any(), any(), any(), any());
    }

    @Test
    void testOnEntryTriggered_NonImpulseStrategyUsesZeroOtm() {
        TradeSignal dtbSignal = TradeSignal.builder()
                .id(7L)
                .symbol("MARUTI")
                .direction(TradeDirection.SHORT)
                .strategyType(StrategyType.DTB)
                .instrumentToken(1001L)
                .build();

        SegmentConfig ceConfig = SegmentConfig.builder()
                .id(12L)
                .symbol("MARUTI")
                .segment(TradingSegment.OPT)
                .enabled(true)
                .build();

        QuoteResult quote = QuoteResult.builder()
                .symbol("MARUTI")
                .ltp(BigDecimal.valueOf(7500))
                .build();

        ResolvedInstrument ceResolved = ResolvedInstrument.builder()
                .tradingSymbol("MARUTI7500CE")
                .instrumentToken(1002L)
                .instrumentType("CE")
                .build();

        QuoteResult ceQuote = QuoteResult.builder()
                .symbol("MARUTI7500CE")
                .ltp(BigDecimal.valueOf(100))
                .build();

        when(segmentConfigRepository.findBySymbolAndEnabledTrue("MARUTI"))
                .thenReturn(Arrays.asList(ceConfig));
        when(marketQuoteService.getQuote("MARUTI", 1001L))
                .thenReturn(quote);
        when(marketQuoteService.getQuote("MARUTI7500CE", 1002L))
                .thenReturn(ceQuote);

        ArgumentCaptor<Double> otmCaptor = ArgumentCaptor.forClass(Double.class);
        when(instrumentResolverService.resolve(eq("MARUTI"), eq(TradingSegment.OPT), eq(TradeDirection.SHORT),
                eq(BigDecimal.valueOf(7500)), otmCaptor.capture()))
                .thenReturn(ceResolved);

        tradeOrchestrationService.onEntryTriggered(dtbSignal);

        // Verify OTM offset of 0.0 for non-IMPULSE strategy
        assertEquals(0.0, otmCaptor.getValue(), "Non-IMPULSE strategy should use 0.0 OTM offset");
        verify(paperOrderExecutionService, times(1)).enter(any(), any(), any(), any(), any());
    }

    @Test
    void testOnEntryTriggered_NoSegmentConfigs_ReturnsImmediately() {
        TradeSignal signal = TradeSignal.builder()
                .id(8L)
                .symbol("UNKNOWN")
                .direction(TradeDirection.LONG)
                .instrumentToken(1101L)
                .build();

        when(segmentConfigRepository.findBySymbolAndEnabledTrue("UNKNOWN"))
                .thenReturn(Collections.emptyList());

        tradeOrchestrationService.onEntryTriggered(signal);

        // Should not call any service methods
        verify(marketQuoteService, never()).getQuote(anyString(), any());
        verify(instrumentResolverService, never()).resolve(anyString(), any(), any(), any(), anyDouble());
        verify(paperOrderExecutionService, never()).enter(any(), any(), any(), any(), any());
    }

    // ============================================================================
    // NEW TESTS: onExitTriggered Safety-Net Coverage
    // ============================================================================

    @Test
    void testOnExitTriggered_MultipleOpenOrders_ExitsEach() {
        TradeSignal signal = TradeSignal.builder()
                .id(9L)
                .symbol("ICICI")
                .build();

        TradeOrder order1 = TradeOrder.builder()
                .id(101L)
                .symbol("ICICI25AUG500CE")
                .instrumentToken(1201L)
                .underlyingSymbol("ICICI")
                .entryPrice(BigDecimal.valueOf(50))
                .direction(TradeDirection.LONG)
                .quantity(100)
                .status(TradeOrderStatus.OPEN)
                .build();

        TradeOrder order2 = TradeOrder.builder()
                .id(102L)
                .symbol("ICICI25AUG520CE")
                .instrumentToken(1202L)
                .underlyingSymbol("ICICI")
                .entryPrice(BigDecimal.valueOf(30))
                .direction(TradeDirection.LONG)
                .quantity(100)
                .status(TradeOrderStatus.OPEN)
                .build();

        QuoteResult quote1 = QuoteResult.builder()
                .symbol("ICICI25AUG500CE")
                .ltp(BigDecimal.valueOf(75))
                .build();

        QuoteResult quote2 = QuoteResult.builder()
                .symbol("ICICI25AUG520CE")
                .ltp(BigDecimal.valueOf(40))
                .build();

        QuoteResult underlyingQuote = QuoteResult.builder()
                .symbol("ICICI")
                .ltp(BigDecimal.valueOf(575))
                .build();

        when(tradeOrderRepository.findBySignalAndStatus(signal, TradeOrderStatus.OPEN))
                .thenReturn(Arrays.asList(order1, order2));
        when(marketQuoteService.getQuote("ICICI25AUG500CE", 1201L))
                .thenReturn(quote1);
        when(marketQuoteService.getQuote("ICICI25AUG520CE", 1202L))
                .thenReturn(quote2);
        when(marketQuoteService.getQuote("ICICI", null))
                .thenReturn(underlyingQuote);

        tradeOrchestrationService.onExitTriggered(signal, ExitReason.TARGET_HIT);

        verify(paperOrderExecutionService, times(2)).exit(any(), any(), any(), any());
    }

    @Test
    void testOnExitTriggered_OrderQuoteUnavailableAtExit_OrderStaysOpen() {
        TradeSignal signal = TradeSignal.builder()
                .id(10L)
                .symbol("AXIS")
                .build();

        TradeOrder order = TradeOrder.builder()
                .id(103L)
                .symbol("AXIS25OCT900CE")
                .instrumentToken(1301L)
                .underlyingSymbol("AXIS")
                .entryPrice(BigDecimal.valueOf(60))
                .direction(TradeDirection.LONG)
                .quantity(50)
                .status(TradeOrderStatus.OPEN)
                .build();

        when(tradeOrderRepository.findBySignalAndStatus(signal, TradeOrderStatus.OPEN))
                .thenReturn(Arrays.asList(order));
        when(marketQuoteService.getQuote("AXIS25OCT900CE", 1301L))
                .thenReturn(null); // Quote unavailable

        tradeOrchestrationService.onExitTriggered(signal, ExitReason.STOP_HIT);

        // Should not exit when quote is unavailable
        verify(paperOrderExecutionService, never()).exit(any(), any(), any(), any());
    }

    @Test
    void testOnExitTriggered_PaperOrderExitException_CatchesContinues() {
        TradeSignal signal = TradeSignal.builder()
                .id(11L)
                .symbol("KOTAK")
                .build();

        TradeOrder order1 = TradeOrder.builder()
                .id(104L)
                .symbol("KOTAKFEB24CE")
                .instrumentToken(1401L)
                .underlyingSymbol("KOTAK")
                .entryPrice(BigDecimal.valueOf(100))
                .direction(TradeDirection.LONG)
                .quantity(25)
                .status(TradeOrderStatus.OPEN)
                .build();

        TradeOrder order2 = TradeOrder.builder()
                .id(105L)
                .symbol("KOTAKFEB24PE")
                .instrumentToken(1402L)
                .underlyingSymbol("KOTAK")
                .entryPrice(BigDecimal.valueOf(80))
                .direction(TradeDirection.SHORT)
                .quantity(25)
                .status(TradeOrderStatus.OPEN)
                .build();

        QuoteResult ceQuote = QuoteResult.builder()
                .symbol("KOTAKFEB24CE")
                .ltp(BigDecimal.valueOf(120))
                .build();

        QuoteResult peQuote = QuoteResult.builder()
                .symbol("KOTAKFEB24PE")
                .ltp(BigDecimal.valueOf(70))
                .build();

        QuoteResult underlyingQuote = QuoteResult.builder()
                .symbol("KOTAK")
                .ltp(BigDecimal.valueOf(1200))
                .build();

        when(tradeOrderRepository.findBySignalAndStatus(signal, TradeOrderStatus.OPEN))
                .thenReturn(Arrays.asList(order1, order2));
        when(marketQuoteService.getQuote("KOTAKFEB24CE", 1401L))
                .thenReturn(ceQuote);
        when(marketQuoteService.getQuote("KOTAKFEB24PE", 1402L))
                .thenReturn(peQuote);
        when(marketQuoteService.getQuote("KOTAK", null))
                .thenReturn(underlyingQuote);

        // First exit throws exception
        doThrow(new RuntimeException("Exit failed for order 1"))
                .when(paperOrderExecutionService).exit(eq(order1), any(), any(), any());

        tradeOrchestrationService.onExitTriggered(signal, ExitReason.REVERSAL_CANDLE);

        // Should attempt to exit both despite first failure
        ArgumentCaptor<TradeOrder> orderCaptor = ArgumentCaptor.forClass(TradeOrder.class);
        verify(paperOrderExecutionService, times(2)).exit(orderCaptor.capture(), any(), any(), any());
        // Verify both orders were attempted to be exited
        assertTrue(orderCaptor.getAllValues().stream().anyMatch(o -> o.getId() == 104L),
                "Order 1 should be attempted");
        assertTrue(orderCaptor.getAllValues().stream().anyMatch(o -> o.getId() == 105L),
                "Order 2 should still be attempted after order 1 failure");
    }

    @Test
    void testOnExitTriggered_UnderlyingQuoteUnavailableAtExit_ContinuiesWithNullLtp() {
        TradeSignal signal = TradeSignal.builder()
                .id(12L)
                .symbol("HDFCBANK")
                .build();

        TradeOrder order = TradeOrder.builder()
                .id(106L)
                .symbol("HDFCBANKFEB24")
                .instrumentToken(1501L)
                .underlyingSymbol("HDFCBANK")
                .entryPrice(BigDecimal.valueOf(1500))
                .direction(TradeDirection.LONG)
                .quantity(10)
                .status(TradeOrderStatus.OPEN)
                .build();

        QuoteResult orderQuote = QuoteResult.builder()
                .symbol("HDFCBANKFEB24")
                .ltp(BigDecimal.valueOf(1550))
                .build();

        when(tradeOrderRepository.findBySignalAndStatus(signal, TradeOrderStatus.OPEN))
                .thenReturn(Arrays.asList(order));
        when(marketQuoteService.getQuote("HDFCBANKFEB24", 1501L))
                .thenReturn(orderQuote);
        when(marketQuoteService.getQuote("HDFCBANK", null))
                .thenReturn(null); // Underlying quote unavailable

        tradeOrchestrationService.onExitTriggered(signal, ExitReason.TARGET_HIT);

        ArgumentCaptor<BigDecimal> underlyingLtpCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(paperOrderExecutionService, times(1)).exit(
                eq(order),
                eq(orderQuote),
                underlyingLtpCaptor.capture(),
                eq(ExitReason.TARGET_HIT)
        );

        // Underlying LTP should be null when quote unavailable
        assertNull(underlyingLtpCaptor.getValue(), "Underlying LTP should be null when quote unavailable");
    }

    @Test
    void testOnExitTriggered_NoOpenOrders_DoesNothing() {
        TradeSignal signal = TradeSignal.builder()
                .id(13L)
                .symbol("NOMURA")
                .build();

        when(tradeOrderRepository.findBySignalAndStatus(signal, TradeOrderStatus.OPEN))
                .thenReturn(Collections.emptyList());

        tradeOrchestrationService.onExitTriggered(signal, ExitReason.STOP_HIT);

        verify(paperOrderExecutionService, never()).exit(any(), any(), any(), any());
    }
}
