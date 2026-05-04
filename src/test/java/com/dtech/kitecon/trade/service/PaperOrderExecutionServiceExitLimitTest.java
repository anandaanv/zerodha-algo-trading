package com.dtech.kitecon.trade.service;

import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.market.orders.OrderException;
import com.dtech.kitecon.market.orders.OrderManager;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.trade.dto.QuoteResult;
import com.dtech.kitecon.trade.entity.TradeOrder;
import com.dtech.kitecon.trade.entity.TradeSignal;
import com.dtech.kitecon.trade.enums.ExitReason;
import com.dtech.kitecon.trade.enums.TradeDirection;
import com.dtech.kitecon.trade.enums.TradeOrderStatus;
import com.dtech.kitecon.trade.enums.TradingSegment;
import com.dtech.kitecon.trade.repository.TradeOrderRepository;
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
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaperOrderExecutionServiceExitLimitTest {

    @Mock
    private TradeOrderRepository tradeOrderRepository;

    @Mock
    private CapitalAllocationService capitalAllocationService;

    @Mock
    private OrderManager orderManager;

    @Mock
    private InstrumentRepository instrumentRepository;

    @InjectMocks
    private PaperOrderExecutionService service;

    private TradeOrder order;
    private QuoteResult quote;
    private Instrument kiteInstrument;

    @BeforeEach
    void setUp() {
        // Set exit slippage to 0.5% for testing
        ReflectionTestUtils.setField(service, "exitLimitSlippagePct", 0.5);
        ReflectionTestUtils.setField(service, "liveOrdersEnabled", true);

        // Create a sample live trade order
        TradeSignal signal = TradeSignal.builder().id(1L).symbol("SBIN").build();
        order = TradeOrder.builder()
                .id(1L)
                .signal(signal)
                .symbol("SBIN-EQ")
                .underlyingSymbol("SBIN")
                .direction(TradeDirection.LONG)
                .quantity(50)
                .entryPrice(BigDecimal.valueOf(500.00))
                .status(TradeOrderStatus.OPEN)
                .paperTrade(false) // Live trade
                .instrumentType("EQ")
                .instrumentToken(1L)
                .entryTime(Instant.now())
                .segment(TradingSegment.EQ)
                .lotSize(1)
                .build();

        // Quote with bid/ask prices
        quote = QuoteResult.builder()
                .ltp(BigDecimal.valueOf(510.00))
                .bidPrice(BigDecimal.valueOf(509.95))
                .askPrice(BigDecimal.valueOf(510.05))
                .build();

        kiteInstrument = new Instrument();
        kiteInstrument.setTradingsymbol("SBIN");
        kiteInstrument.setExchange("BSE");
    }

    @Test
    void testSellExitLimitPrice() throws OrderException {
        // LONG position → SELL exit
        // Exit price (bid) = 509.95
        // Slippage 0.5% below bid: 509.95 * (1 - 0.005) = 509.45
        // After rounding to tick 0.05: should be 509.45

        when(tradeOrderRepository.save(any())).thenReturn(order);
        when(instrumentRepository.findAllByTradingsymbolAndExchangeIn(any(), any()))
                .thenReturn(Collections.singletonList(kiteInstrument));
        when(orderManager.placeOrder(anyDouble(), anyInt(), any(), any(), any()))
                .thenReturn("order-123");

        service.exit(order, quote, null, ExitReason.TARGET_HIT);

        ArgumentCaptor<Double> priceCaptor = ArgumentCaptor.forClass(Double.class);
        verify(orderManager).placeOrder(
                priceCaptor.capture(),
                eq(50),
                eq(kiteInstrument),
                eq("SELL"),
                eq("MIS"));

        double limitPrice = priceCaptor.getValue();
        // Expected: 509.95 * (1 - 0.005) = 509.450... ≈ 509.45
        double expected = 509.95 * (1.0 - 0.005);
        expected = Math.max(0.05, Math.round(expected * 20.0) / 20.0);

        assertEquals(expected, limitPrice, 0.01, "SELL exit limit price should be bid - slippage%");
    }

    @Test
    void testBuyExitLimitPrice() throws OrderException {
        // SHORT position → BUY exit
        // Exit price (ask) = 510.05
        // Slippage 0.5% above ask: 510.05 * (1 + 0.005) = 512.60
        // After rounding to tick 0.05: should be 512.60

        order.setDirection(TradeDirection.SHORT);

        when(tradeOrderRepository.save(any())).thenReturn(order);
        when(instrumentRepository.findAllByTradingsymbolAndExchangeIn(any(), any()))
                .thenReturn(Collections.singletonList(kiteInstrument));
        when(orderManager.placeOrder(anyDouble(), anyInt(), any(), any(), any()))
                .thenReturn("order-456");

        service.exit(order, quote, null, ExitReason.STOP_HIT);

        ArgumentCaptor<Double> priceCaptor = ArgumentCaptor.forClass(Double.class);
        verify(orderManager).placeOrder(
                priceCaptor.capture(),
                eq(50),
                eq(kiteInstrument),
                eq("BUY"),
                eq("MIS"));

        double limitPrice = priceCaptor.getValue();
        // Expected: 510.05 * (1 + 0.005) = 512.60...
        double expected = 510.05 * (1.0 + 0.005);
        expected = Math.max(0.05, Math.round(expected * 20.0) / 20.0);

        assertEquals(expected, limitPrice, 0.01, "BUY exit limit price should be ask + slippage%");
    }

    @Test
    void testLimitPriceRoundedToTick() throws OrderException {
        // Test rounding to 0.05 tick size
        // Entry: 500, quote bid: 509.92
        // SELL at 509.92 * (1 - 0.005) = 509.42
        // Rounded to 0.05: 509.40

        quote = QuoteResult.builder()
                .ltp(BigDecimal.valueOf(509.92))
                .bidPrice(BigDecimal.valueOf(509.92))
                .askPrice(BigDecimal.valueOf(509.97))
                .build();

        when(tradeOrderRepository.save(any())).thenReturn(order);
        when(instrumentRepository.findAllByTradingsymbolAndExchangeIn(any(), any()))
                .thenReturn(Collections.singletonList(kiteInstrument));
        when(orderManager.placeOrder(anyDouble(), anyInt(), any(), any(), any()))
                .thenReturn("order-789");

        service.exit(order, quote, null, ExitReason.TARGET_HIT);

        ArgumentCaptor<Double> priceCaptor = ArgumentCaptor.forClass(Double.class);
        verify(orderManager).placeOrder(priceCaptor.capture(), anyInt(), any(), any(), any());

        double limitPrice = priceCaptor.getValue();
        // Verify it's rounded to a multiple of 0.05
        double fractional = limitPrice % 0.05;
        assertTrue(fractional < 0.0001 || fractional > 0.0499,
                "Limit price should be rounded to 0.05 tick: " + limitPrice);
    }

    @Test
    void testLimitPriceMinimum() throws OrderException {
        // Ensure limit price never goes below 0.05 (minimum tick)
        quote = QuoteResult.builder()
                .ltp(BigDecimal.valueOf(0.03))
                .bidPrice(BigDecimal.valueOf(0.03))
                .askPrice(BigDecimal.valueOf(0.04))
                .build();

        when(tradeOrderRepository.save(any())).thenReturn(order);
        when(instrumentRepository.findAllByTradingsymbolAndExchangeIn(any(), any()))
                .thenReturn(Collections.singletonList(kiteInstrument));
        when(orderManager.placeOrder(anyDouble(), anyInt(), any(), any(), any()))
                .thenReturn("order-min");

        service.exit(order, quote, null, ExitReason.STOP_HIT);

        ArgumentCaptor<Double> priceCaptor = ArgumentCaptor.forClass(Double.class);
        verify(orderManager).placeOrder(priceCaptor.capture(), anyInt(), any(), any(), any());

        double limitPrice = priceCaptor.getValue();
        assertTrue(limitPrice >= 0.05, "Limit price should never be below 0.05 minimum tick");
    }

    @Test
    void testExitOrderPlacedWithLogging() throws OrderException {
        // Verify that exit order is placed and logged correctly
        when(tradeOrderRepository.save(any())).thenReturn(order);
        when(instrumentRepository.findAllByTradingsymbolAndExchangeIn(any(), any()))
                .thenReturn(Collections.singletonList(kiteInstrument));
        when(orderManager.placeOrder(anyDouble(), anyInt(), any(), any(), any()))
                .thenReturn("order-log-123");

        service.exit(order, quote, null, ExitReason.TARGET_HIT);

        // Verify placeOrder was called exactly once with correct direction
        verify(orderManager, times(1)).placeOrder(
                anyDouble(),
                eq(50),
                eq(kiteInstrument),
                eq("SELL"),
                eq("MIS"));

        // Verify order was saved with CLOSED status
        verify(tradeOrderRepository, times(1)).save(argThat(o ->
                o.getStatus() == TradeOrderStatus.CLOSED &&
                        o.getExitReason() == ExitReason.TARGET_HIT));
    }

    @Test
    void testPaperTradeSkipsLivePlacement() throws OrderException {
        // Paper trade should not place broker order
        order.setPaperTrade(true);

        when(tradeOrderRepository.save(any())).thenReturn(order);

        service.exit(order, quote, null, ExitReason.STOP_HIT);

        // orderManager.placeOrder should NOT be called for paper trades
        verify(orderManager, never()).placeOrder(anyDouble(), anyInt(), any(), any(), any());

        // But order should still be saved
        verify(tradeOrderRepository, times(1)).save(any());
    }

    @Test
    void testLiveOrdersDisabledSkipsPlacement() throws OrderException {
        // When liveOrdersEnabled=false, should not place broker order
        ReflectionTestUtils.setField(service, "liveOrdersEnabled", false);

        when(tradeOrderRepository.save(any())).thenReturn(order);

        service.exit(order, quote, null, ExitReason.TARGET_HIT);

        // orderManager.placeOrder should NOT be called
        verify(orderManager, never()).placeOrder(anyDouble(), anyInt(), any(), any(), any());

        // But order should still be saved
        verify(tradeOrderRepository, times(1)).save(any());
    }
}
