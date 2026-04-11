package com.dtech.kitecon.trade.integration;

import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.trade.dto.QuoteResult;
import com.dtech.kitecon.trade.entity.SegmentConfig;
import com.dtech.kitecon.trade.entity.TradeOrder;
import com.dtech.kitecon.trade.enums.*;
import com.dtech.kitecon.trade.repository.SegmentConfigRepository;
import com.dtech.kitecon.trade.repository.TradeOrderRepository;
import com.dtech.kitecon.trade.repository.TradeSignalRepository;
import com.dtech.kitecon.trade.service.BrokerOrderService;
import com.dtech.kitecon.trade.service.MarketQuoteService;
import com.dtech.kitecon.trade.service.TradeEntryHandler;
import com.dtech.kitecon.trade.service.TradeExitHandler;
import com.dtech.kitecon.trade.entity.TradeSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest
@Transactional
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
@Sql(scripts = "/sql/trading-test-setup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class TradingSystemIntegrationTest {

    @Autowired
    private TradeSignalRepository tradeSignalRepository;

    @Autowired
    private TradeOrderRepository tradeOrderRepository;

    @Autowired
    private SegmentConfigRepository segmentConfigRepository;

    @Autowired
    private InstrumentRepository instrumentRepository;

    @Autowired
    private TradeEntryHandler tradeEntryHandler;

    @Autowired
    private TradeExitHandler tradeExitHandler;

    @MockBean
    private MarketQuoteService marketQuoteService;

    @MockBean
    private BrokerOrderService brokerOrderService;

    private static final String TEST_SYMBOL = "TESTREL";
    private static final Long TEST_INSTRUMENT_TOKEN = 99999L;

    @BeforeEach
    void setUp() {
        Instrument testInstrument = Instrument.builder()
                .instrumentToken(TEST_INSTRUMENT_TOKEN)
                .tradingsymbol(TEST_SYMBOL)
                .name("Test Reliance")
                .exchange("NSE")
                .instrumentType("EQ")
                .segment("NSE")
                .lotSize(1)
                .build();

        instrumentRepository.save(testInstrument);

        SegmentConfig config = SegmentConfig.builder()
                .symbol(TEST_SYMBOL)
                .segment(TradingSegment.EQ)
                .enabled(true)
                .capitalPct(BigDecimal.valueOf(5.0))
                .provider("KITE")
                .build();

        segmentConfigRepository.save(config);
    }

    @Test
    void testFullTradeLifecycle() {
        QuoteResult ltp1000 = QuoteResult.builder()
                .symbol(TEST_SYMBOL)
                .instrumentToken(TEST_INSTRUMENT_TOKEN)
                .ltp(BigDecimal.valueOf(1000))
                .askPrice(BigDecimal.valueOf(1001))
                .bidPrice(BigDecimal.valueOf(999))
                .build();

        QuoteResult ltp1100 = QuoteResult.builder()
                .symbol(TEST_SYMBOL)
                .instrumentToken(TEST_INSTRUMENT_TOKEN)
                .ltp(BigDecimal.valueOf(1100))
                .askPrice(BigDecimal.valueOf(1101))
                .bidPrice(BigDecimal.valueOf(1099))
                .build();

        // BrokerOrderService drives TradeEntryHandler/TradeExitHandler LTP checks
        when(brokerOrderService.fetchLtp(TEST_SYMBOL, TEST_INSTRUMENT_TOKEN))
                .thenReturn(BigDecimal.valueOf(1000));
        // MarketQuoteService drives TradeOrchestrationService order creation
        when(marketQuoteService.getQuote(anyString(), any()))
                .thenReturn(ltp1000);

        TradeSignal signal = TradeSignal.builder()
                .symbol(TEST_SYMBOL)
                .exchange("NSE")
                .instrumentType("EQ")
                .instrumentToken(TEST_INSTRUMENT_TOKEN)
                .direction(TradeDirection.LONG)
                .patternType("DOUBLE_BOTTOM")
                .confirmationType("WATCHING")
                .entryPrice(BigDecimal.valueOf(990))
                .stopLoss(BigDecimal.valueOf(950))
                .target(BigDecimal.valueOf(1100))
                .neckline(BigDecimal.valueOf(990))
                .patternHeight(BigDecimal.valueOf(100))
                .timeframe("1h")
                .signalTime(Instant.now())
                .entryValidUntil(Instant.now().plusSeconds(3600))
                .status(TradeStatus.WATCHING_ENTRY)
                .lotSize(1)
                .rrRatio(BigDecimal.valueOf(2.5))
                .build();

        signal = tradeSignalRepository.save(signal);

        assertEquals(TradeStatus.WATCHING_ENTRY, signal.getStatus());

        tradeEntryHandler.handle(signal, true);

        Optional<TradeSignal> activeSignal = tradeSignalRepository.findById(signal.getId());
        assertTrue(activeSignal.isPresent());
        assertEquals(TradeStatus.ACTIVE, activeSignal.get().getStatus());

        List<TradeOrder> openOrders = tradeOrderRepository.findBySignalAndStatus(
                activeSignal.get(), TradeOrderStatus.OPEN);
        assertTrue(openOrders.size() > 0);

        TradeOrder order = openOrders.get(0);
        assertEquals(TradeDirection.LONG, order.getDirection());
        assertEquals(TradeOrderStatus.OPEN, order.getStatus());
        assertEquals(BigDecimal.valueOf(1001), order.getEntryPrice());

        when(brokerOrderService.fetchLtp(TEST_SYMBOL, TEST_INSTRUMENT_TOKEN))
                .thenReturn(BigDecimal.valueOf(1100));
        when(marketQuoteService.getQuote(anyString(), any()))
                .thenReturn(ltp1100);

        tradeExitHandler.handle(activeSignal.get(), true);

        Optional<TradeSignal> completedSignal = tradeSignalRepository.findById(signal.getId());
        assertTrue(completedSignal.isPresent());
        assertEquals(TradeStatus.COMPLETED, completedSignal.get().getStatus());

        List<TradeOrder> closedOrders = tradeOrderRepository.findBySignalAndStatus(
                completedSignal.get(), TradeOrderStatus.CLOSED);
        assertTrue(closedOrders.size() > 0);

        TradeOrder closedOrder = closedOrders.get(0);
        assertEquals(TradeOrderStatus.CLOSED, closedOrder.getStatus());
        assertNotNull(closedOrder.getExitPrice());
        assertTrue(closedOrder.getRealisedPnl().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void testSegmentConfigCRUD() {
        SegmentConfig newConfig = SegmentConfig.builder()
                .symbol("INFY")
                .segment(TradingSegment.EQ)
                .enabled(true)
                .capitalPct(BigDecimal.valueOf(3.0))
                .provider("KITE")
                .build();

        SegmentConfig saved = segmentConfigRepository.save(newConfig);
        assertNotNull(saved.getId());

        Optional<SegmentConfig> fetched = segmentConfigRepository.findById(saved.getId());
        assertTrue(fetched.isPresent());
        assertEquals("INFY", fetched.get().getSymbol());

        SegmentConfig updated = fetched.get();
        updated.setCapitalPct(BigDecimal.valueOf(4.0));
        updated.setEnabled(false);

        SegmentConfig savedUpdate = segmentConfigRepository.save(updated);
        assertEquals(BigDecimal.valueOf(4.0), savedUpdate.getCapitalPct());
        assertFalse(savedUpdate.isEnabled());

        segmentConfigRepository.deleteById(saved.getId());
        Optional<SegmentConfig> deleted = segmentConfigRepository.findById(saved.getId());
        assertFalse(deleted.isPresent());
    }
}
