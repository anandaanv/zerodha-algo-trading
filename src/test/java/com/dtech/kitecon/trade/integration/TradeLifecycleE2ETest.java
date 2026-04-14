package com.dtech.kitecon.trade.integration;

import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.trade.dto.QuoteResult;
import com.dtech.kitecon.trade.entity.SegmentConfig;
import com.dtech.kitecon.trade.entity.TradeOrder;
import com.dtech.kitecon.trade.entity.TradeSignal;
import com.dtech.kitecon.trade.enums.*;
import com.dtech.kitecon.trade.repository.SegmentConfigRepository;
import com.dtech.kitecon.trade.repository.TradeOrderRepository;
import com.dtech.kitecon.trade.repository.TradeSignalRepository;
import com.dtech.kitecon.trade.service.BrokerOrderService;
import com.dtech.kitecon.trade.service.MarketQuoteService;
import com.dtech.kitecon.trade.service.RlExitClient;
import com.dtech.kitecon.trade.service.TradeEntryHandler;
import com.dtech.kitecon.trade.service.TradeExitHandler;
import org.junit.jupiter.api.*;
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
import static org.mockito.Mockito.when;

/**
 * End-to-end integration test: simulates 5 trades through the full lifecycle.
 *
 * Trade 1: LONG  DOUBLE_BOTTOM — target hit → COMPLETED (win)
 * Trade 2: SHORT DOUBLE_TOP    — stop loss hit → COMPLETED (loss)
 * Trade 3: LONG  TRENDLINE_BREAKOUT — target hit → COMPLETED (win)
 * Trade 4: SHORT TRIANGLE_DESCENDING — entry expires → EXPIRED
 * Trade 5: LONG  HNS_BULL — SL hit then re-enters as new signal, target hit → COMPLETED
 */
@SpringBootTest
@Transactional
@ActiveProfiles("integration")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "trade.filter.threshold=0.0",
        "rl.exit.enabled=true"
})
@Sql(scripts = "/sql/trading-test-setup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class TradeLifecycleE2ETest {

    @Autowired private TradeSignalRepository tradeSignalRepository;
    @Autowired private TradeOrderRepository tradeOrderRepository;
    @Autowired private SegmentConfigRepository segmentConfigRepository;
    @Autowired private InstrumentRepository instrumentRepository;
    @Autowired private TradeEntryHandler tradeEntryHandler;
    @Autowired private TradeExitHandler tradeExitHandler;

    @MockBean private MarketQuoteService marketQuoteService;
    @MockBean private BrokerOrderService brokerOrderService;
    @MockBean private com.dtech.kitecon.patternscanner.TradeFilterClient tradeFilterClient;
    @MockBean private com.dtech.kitecon.patternscanner.PatternScanService patternScanService;
    @MockBean private RlExitClient rlExitClient;

    private static final Long TOKEN_REL = 99999901L;
    private static final Long TOKEN_TCS = 99999902L;
    private static final Long TOKEN_AXIS = 99999903L;
    private static final Long TOKEN_INFY = 99999904L;
    private static final Long TOKEN_SBIN = 99999905L;

    @BeforeEach
    void setUp() {
        setupInstrument("TEST_REL", TOKEN_REL);
        setupInstrument("TEST_TCS", TOKEN_TCS);
        setupInstrument("TEST_AXIS", TOKEN_AXIS);
        setupInstrument("TEST_INFY", TOKEN_INFY);
        setupInstrument("TEST_SBIN", TOKEN_SBIN);

        // Mock ML filter to always pass
        // score(PatternDto, String, 20x double)
        org.mockito.Mockito.lenient().doReturn(0.95)
                .when(tradeFilterClient).score(
                        any(), any(),                                              // PatternDto, String
                        org.mockito.ArgumentMatchers.anyDouble(),                  // dailyRsi
                        org.mockito.ArgumentMatchers.anyDouble(),                  // dailyAdx
                        org.mockito.ArgumentMatchers.anyDouble(),                  // dailyAdxEma
                        org.mockito.ArgumentMatchers.anyDouble(),                  // adxWatching
                        org.mockito.ArgumentMatchers.anyDouble(),                  // adxWatchingEma
                        org.mockito.ArgumentMatchers.anyDouble(),                  // adxConfirm
                        org.mockito.ArgumentMatchers.anyDouble(),                  // adxConfirmEma
                        org.mockito.ArgumentMatchers.anyDouble(),                  // macdWatching
                        org.mockito.ArgumentMatchers.anyDouble(),                  // macdSignalWatching
                        org.mockito.ArgumentMatchers.anyDouble(),                  // bbWidthWatching
                        org.mockito.ArgumentMatchers.anyDouble(),                  // bbPctBWatching
                        org.mockito.ArgumentMatchers.anyDouble(),                  // macdDaily
                        org.mockito.ArgumentMatchers.anyDouble(),                  // macdSignalDaily
                        org.mockito.ArgumentMatchers.anyDouble(),                  // bbWidthDaily
                        org.mockito.ArgumentMatchers.anyDouble(),                  // bbPctBDaily
                        org.mockito.ArgumentMatchers.anyDouble(),                  // bbExpanding
                        org.mockito.ArgumentMatchers.anyDouble(),                  // bbAligned
                        org.mockito.ArgumentMatchers.anyDouble(),                  // rsiSlope
                        org.mockito.ArgumentMatchers.anyDouble(),                  // macdHistSlope
                        org.mockito.ArgumentMatchers.anyDouble());                 // adxSlope

        // Mock pattern scan service to return a dummy PatternDto with zeros
        com.dtech.kitecon.patternscanner.PatternDto dummyDto = com.dtech.kitecon.patternscanner.PatternDto.builder()
                .patternType("DOUBLE_BOTTOM").bullish(true).build();
        when(patternScanService.computeCurrentIndicators(any())).thenReturn(dummyDto);
    }

    private void setupInstrument(String symbol, Long token) {
        // Clean up any existing test data
        instrumentRepository.findById(token).ifPresent(instrumentRepository::delete);

        Instrument inst = Instrument.builder()
                .instrumentToken(token)
                .tradingsymbol(symbol)
                .name(symbol)
                .exchange("NSE")
                .instrumentType("EQ")
                .segment("NSE")
                .lotSize(1)
                .build();
        instrumentRepository.save(inst);

        SegmentConfig config = SegmentConfig.builder()
                .symbol(symbol)
                .segment(TradingSegment.EQ)
                .enabled(true)
                .capitalPct(BigDecimal.valueOf(5.0))
                .provider("KITE")
                .build();
        segmentConfigRepository.save(config);
    }

    private QuoteResult quote(String symbol, Long token, double ltp) {
        return QuoteResult.builder()
                .symbol(symbol)
                .instrumentToken(token)
                .ltp(BigDecimal.valueOf(ltp))
                .askPrice(BigDecimal.valueOf(ltp + 1))
                .bidPrice(BigDecimal.valueOf(ltp - 1))
                .build();
    }

    private TradeSignal createSignal(String symbol, Long token, TradeDirection dir,
                                      String pattern, double entry, double sl, double target) {
        return tradeSignalRepository.save(TradeSignal.builder()
                .symbol(symbol)
                .exchange("NSE")
                .instrumentType("EQ")
                .instrumentToken(token)
                .direction(dir)
                .patternType(pattern)
                .confirmationType("WATCHING")
                .entryPrice(BigDecimal.valueOf(entry))
                .stopLoss(BigDecimal.valueOf(sl))
                .target(BigDecimal.valueOf(target))
                .neckline(BigDecimal.valueOf(entry))
                .patternHeight(BigDecimal.valueOf(Math.abs(target - entry)))
                .timeframe("1h")
                .signalTime(Instant.now())
                .entryValidUntil(Instant.now().plusSeconds(3600))
                .status(TradeStatus.WATCHING_ENTRY)
                .lotSize(1)
                .rrRatio(BigDecimal.valueOf(2.0))
                .mlScore(BigDecimal.valueOf(0.85))
                .build());
    }

    // ──────────────────────────────────────────────────────────────────
    // Trade 1: LONG DOUBLE_BOTTOM — price above entry → ACTIVE → target hit → COMPLETED
    // ──────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Trade 1: LONG TEST_REL DOUBLE_BOTTOM — target hit = WIN")
    void trade1_longDoubleBottom_targetHit() {
        // Entry at 1300, SL at 1260, Target at 1380
        when(brokerOrderService.fetchLtp("TEST_REL", TOKEN_REL)).thenReturn(BigDecimal.valueOf(1310));
        when(marketQuoteService.getQuote(anyString(), any())).thenReturn(quote("TEST_REL", TOKEN_REL, 1310));

        TradeSignal signal = createSignal("TEST_REL", TOKEN_REL, TradeDirection.LONG,
                "DOUBLE_BOTTOM", 1300, 1260, 1380);

        // Step 1: Entry — LTP 1310 > entry 1300 → should enter
        tradeEntryHandler.handle(signal, true);
        signal = tradeSignalRepository.findById(signal.getId()).orElseThrow();
        assertEquals(TradeStatus.ACTIVE, signal.getStatus(), "Should be ACTIVE after entry");

        // Verify TradeOrder created
        List<TradeOrder> orders = tradeOrderRepository.findBySignalAndStatus(signal, TradeOrderStatus.OPEN);
        assertFalse(orders.isEmpty(), "Should have open orders");

        // Step 2: Price moves to target — LTP 1385 > target 1380
        when(brokerOrderService.fetchLtp("TEST_REL", TOKEN_REL)).thenReturn(BigDecimal.valueOf(1385));
        when(marketQuoteService.getQuote(anyString(), any())).thenReturn(quote("TEST_REL", TOKEN_REL, 1385));

        tradeExitHandler.handle(signal, true);
        signal = tradeSignalRepository.findById(signal.getId()).orElseThrow();
        assertEquals(TradeStatus.COMPLETED, signal.getStatus(), "Should be COMPLETED after target hit");

        // Verify order closed with profit
        List<TradeOrder> closed = tradeOrderRepository.findBySignalAndStatus(signal, TradeOrderStatus.CLOSED);
        assertFalse(closed.isEmpty(), "Should have closed orders");
        assertTrue(closed.get(0).getRealisedPnl().compareTo(BigDecimal.ZERO) > 0, "Should be profitable");
    }

    // ──────────────────────────────────────────────────────────────────
    // Trade 2: SHORT DOUBLE_TOP — price below entry → ACTIVE → SL hit → COMPLETED
    // ──────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Trade 2: SHORT TEST_TCS DOUBLE_TOP — stop loss hit = LOSS")
    void trade2_shortDoubleTop_slHit() {
        // Entry at 4200, SL at 4280, Target at 4050
        when(brokerOrderService.fetchLtp("TEST_TCS", TOKEN_TCS)).thenReturn(BigDecimal.valueOf(4190));
        when(marketQuoteService.getQuote(anyString(), any())).thenReturn(quote("TEST_TCS", TOKEN_TCS, 4190));

        TradeSignal signal = createSignal("TEST_TCS", TOKEN_TCS, TradeDirection.SHORT,
                "DOUBLE_TOP", 4200, 4280, 4050);

        // Step 1: Entry — LTP 4190 < entry 4200 → should enter short
        tradeEntryHandler.handle(signal, true);
        signal = tradeSignalRepository.findById(signal.getId()).orElseThrow();
        assertEquals(TradeStatus.ACTIVE, signal.getStatus(), "Should be ACTIVE after short entry");

        // Step 2: Price reverses to SL — LTP 4290 > SL 4280
        when(brokerOrderService.fetchLtp("TEST_TCS", TOKEN_TCS)).thenReturn(BigDecimal.valueOf(4290));
        when(marketQuoteService.getQuote(anyString(), any())).thenReturn(quote("TEST_TCS", TOKEN_TCS, 4290));

        tradeExitHandler.handle(signal, true);
        signal = tradeSignalRepository.findById(signal.getId()).orElseThrow();
        assertEquals(TradeStatus.COMPLETED, signal.getStatus(), "Should be COMPLETED after SL hit");

        // Verify loss
        List<TradeOrder> closed = tradeOrderRepository.findBySignalAndStatus(signal, TradeOrderStatus.CLOSED);
        assertFalse(closed.isEmpty());
        assertTrue(closed.get(0).getRealisedPnl().compareTo(BigDecimal.ZERO) < 0, "Should be a loss");
    }

    // ──────────────────────────────────────────────────────────────────
    // Trade 3: LONG TRENDLINE_BREAKOUT — entry → target hit → COMPLETED
    // ──────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Trade 3: LONG TEST_AXIS TRENDLINE_BREAKOUT — target hit = WIN")
    void trade3_longTrendlineBreakout_targetHit() {
        // Entry at 1340, SL at 1310, Target at 1410
        when(brokerOrderService.fetchLtp("TEST_AXIS", TOKEN_AXIS)).thenReturn(BigDecimal.valueOf(1345));
        when(marketQuoteService.getQuote(anyString(), any())).thenReturn(quote("TEST_AXIS", TOKEN_AXIS, 1345));

        TradeSignal signal = createSignal("TEST_AXIS", TOKEN_AXIS, TradeDirection.LONG,
                "TRENDLINE_BREAKOUT", 1340, 1310, 1410);

        // Entry
        tradeEntryHandler.handle(signal, true);
        signal = tradeSignalRepository.findById(signal.getId()).orElseThrow();
        assertEquals(TradeStatus.ACTIVE, signal.getStatus());

        // Target hit
        when(brokerOrderService.fetchLtp("TEST_AXIS", TOKEN_AXIS)).thenReturn(BigDecimal.valueOf(1415));
        when(marketQuoteService.getQuote(anyString(), any())).thenReturn(quote("TEST_AXIS", TOKEN_AXIS, 1415));

        tradeExitHandler.handle(signal, true);
        signal = tradeSignalRepository.findById(signal.getId()).orElseThrow();
        assertEquals(TradeStatus.COMPLETED, signal.getStatus());

        List<TradeOrder> closed = tradeOrderRepository.findBySignalAndStatus(signal, TradeOrderStatus.CLOSED);
        assertFalse(closed.isEmpty());
        assertTrue(closed.get(0).getRealisedPnl().compareTo(BigDecimal.ZERO) > 0, "TLB trade should be profitable");
    }

    // ──────────────────────────────────────────────────────────────────
    // Trade 4: SHORT TRIANGLE — price doesn't reach entry → EXPIRED
    // ──────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Trade 4: SHORT TEST_INFY TRIANGLE — entry window expires = EXPIRED")
    void trade4_shortTriangle_expired() {
        // Entry at 1500, but LTP stays at 1520 (above entry for SHORT)
        when(brokerOrderService.fetchLtp("TEST_INFY", TOKEN_INFY)).thenReturn(BigDecimal.valueOf(1520));
        when(marketQuoteService.getQuote(anyString(), any())).thenReturn(quote("TEST_INFY", TOKEN_INFY, 1520));

        TradeSignal signal = tradeSignalRepository.save(TradeSignal.builder()
                .symbol("TEST_INFY")
                .exchange("NSE")
                .instrumentType("EQ")
                .instrumentToken(TOKEN_INFY)
                .direction(TradeDirection.SHORT)
                .patternType("TRIANGLE_DESCENDING")
                .confirmationType("WATCHING")
                .entryPrice(BigDecimal.valueOf(1500))
                .stopLoss(BigDecimal.valueOf(1540))
                .target(BigDecimal.valueOf(1420))
                .neckline(BigDecimal.valueOf(1500))
                .patternHeight(BigDecimal.valueOf(80))
                .timeframe("1h")
                .signalTime(Instant.now())
                .entryValidUntil(Instant.now().minusSeconds(60)) // already expired!
                .status(TradeStatus.WATCHING_ENTRY)
                .lotSize(1)
                .rrRatio(BigDecimal.valueOf(2.0))
                .mlScore(BigDecimal.valueOf(0.85))
                .build());

        // Handle — should detect expired entry window
        tradeEntryHandler.handle(signal, true);
        signal = tradeSignalRepository.findById(signal.getId()).orElseThrow();
        assertEquals(TradeStatus.EXPIRED, signal.getStatus(), "Should be EXPIRED — entry window passed");
    }

    // ──────────────────────────────────────────────────────────────────
    // Trade 5: LONG HNS_BULL — enters → holds (no exit trigger) → then target hit
    // ──────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Trade 5: LONG TEST_SBIN HNS_BULL — hold then target hit = WIN")
    void trade5_longHnsBull_holdThenTarget() {
        // Entry at 820, SL at 790, Target at 870
        when(brokerOrderService.fetchLtp("TEST_SBIN", TOKEN_SBIN)).thenReturn(BigDecimal.valueOf(825));
        when(marketQuoteService.getQuote(anyString(), any())).thenReturn(quote("TEST_SBIN", TOKEN_SBIN, 825));

        TradeSignal signal = createSignal("TEST_SBIN", TOKEN_SBIN, TradeDirection.LONG,
                "HNS_BULL", 820, 790, 870);

        // Step 1: Entry
        tradeEntryHandler.handle(signal, true);
        signal = tradeSignalRepository.findById(signal.getId()).orElseThrow();
        assertEquals(TradeStatus.ACTIVE, signal.getStatus());

        // Step 2: Price moves sideways — LTP 830, no exit trigger
        when(brokerOrderService.fetchLtp("TEST_SBIN", TOKEN_SBIN)).thenReturn(BigDecimal.valueOf(830));
        when(marketQuoteService.getQuote(anyString(), any())).thenReturn(quote("TEST_SBIN", TOKEN_SBIN, 830));

        tradeExitHandler.handle(signal, true);
        signal = tradeSignalRepository.findById(signal.getId()).orElseThrow();
        assertEquals(TradeStatus.ACTIVE, signal.getStatus(), "Should still be ACTIVE — no exit trigger");

        // Step 3: Price moves to target — LTP 875 > target 870
        when(brokerOrderService.fetchLtp("TEST_SBIN", TOKEN_SBIN)).thenReturn(BigDecimal.valueOf(875));
        when(marketQuoteService.getQuote(anyString(), any())).thenReturn(quote("TEST_SBIN", TOKEN_SBIN, 875));

        tradeExitHandler.handle(signal, true);
        signal = tradeSignalRepository.findById(signal.getId()).orElseThrow();
        assertEquals(TradeStatus.COMPLETED, signal.getStatus(), "Should be COMPLETED — target hit");

        List<TradeOrder> closed = tradeOrderRepository.findBySignalAndStatus(signal, TradeOrderStatus.CLOSED);
        assertFalse(closed.isEmpty());
        assertTrue(closed.get(0).getRealisedPnl().compareTo(BigDecimal.ZERO) > 0);
    }

    // ──────────────────────────────────────────────────────────────────
    // Trade 6: LONG DTB — RL agent exits before target → COMPLETED
    // ──────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Trade 6: LONG DTB — RL agent recommends exit before target = WIN")
    void trade6_longDtb_rlExit() {
        // Entry at 500, SL at 470, Target at 560
        when(brokerOrderService.fetchLtp("TEST_REL", TOKEN_REL)).thenReturn(BigDecimal.valueOf(505));
        when(marketQuoteService.getQuote(anyString(), any())).thenReturn(quote("TEST_REL", TOKEN_REL, 505));

        TradeSignal signal = createSignal("TEST_REL", TOKEN_REL, TradeDirection.LONG,
                "DOUBLE_BOTTOM", 500, 470, 560);

        // Step 1: Entry
        tradeEntryHandler.handle(signal, true);
        signal = tradeSignalRepository.findById(signal.getId()).orElseThrow();
        assertEquals(TradeStatus.ACTIVE, signal.getStatus(), "Should be ACTIVE after entry");

        // Step 2: Price moves to 530 — above entry but below target (560), above SL (470)
        // RL agent should be consulted. Mock it to return EXIT.
        when(brokerOrderService.fetchLtp("TEST_REL", TOKEN_REL)).thenReturn(BigDecimal.valueOf(530));
        when(marketQuoteService.getQuote(anyString(), any())).thenReturn(quote("TEST_REL", TOKEN_REL, 530));

        // Mock the RL exit client to return EXIT
        org.mockito.Mockito.lenient().doReturn("EXIT").when(rlExitClient).predict(
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), anyString(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble());

        tradeExitHandler.handle(signal, true);
        signal = tradeSignalRepository.findById(signal.getId()).orElseThrow();
        assertEquals(TradeStatus.COMPLETED, signal.getStatus(), "Should be COMPLETED — RL agent said EXIT");

        // Verify profitable exit (entered ~501 ask, exited at 530)
        List<TradeOrder> closed = tradeOrderRepository.findBySignalAndStatus(signal, TradeOrderStatus.CLOSED);
        assertFalse(closed.isEmpty());
        assertTrue(closed.get(0).getRealisedPnl().compareTo(BigDecimal.ZERO) > 0, "Should be profitable — RL exit at 530 > entry ~501");
    }

    // ──────────────────────────────────────────────────────────────────
    // Run all 5 trades sequentially (summary test)
    // ──────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("All 5 trades reach terminal state")
    void allTradesReachTerminalState() {
        trade1_longDoubleBottom_targetHit();
        trade2_shortDoubleTop_slHit();
        trade3_longTrendlineBreakout_targetHit();
        trade4_shortTriangle_expired();
        trade5_longHnsBull_holdThenTarget();

        // Verify all signals are in terminal state
        List<TradeSignal> allSignals = tradeSignalRepository.findAll();
        for (TradeSignal s : allSignals) {
            assertTrue(
                s.getStatus() == TradeStatus.COMPLETED || s.getStatus() == TradeStatus.EXPIRED,
                "Signal " + s.getId() + " (" + s.getPatternType() + ") should be terminal, got: " + s.getStatus()
            );
        }
        System.out.println("✓ All " + allSignals.size() + " trades reached terminal state");
    }
}
