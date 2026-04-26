package com.dtech.kitecon.trade.service;

import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.trade.dto.ResolvedInstrument;
import com.dtech.kitecon.trade.enums.TradeDirection;
import com.dtech.kitecon.trade.enums.TradingSegment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InstrumentResolverServiceTest {

    @Mock
    private InstrumentRepository instrumentRepository;

    @InjectMocks
    private InstrumentResolverService instrumentResolverService;

    @Test
    void testResolveEQ() {
        Instrument inst = Instrument.builder()
                .instrumentToken(12345L)
                .tradingsymbol("RELIANCE")
                .exchange("BSE")
                .lotSize(1)
                .instrumentType("EQ")
                .build();

        when(instrumentRepository.findAllByTradingsymbolAndExchangeIn("RELIANCE", new String[]{"BSE", "NSE"}))
                .thenReturn(List.of(inst));

        ResolvedInstrument resolved = instrumentResolverService.resolve(
                "RELIANCE", TradingSegment.EQ, TradeDirection.LONG, BigDecimal.valueOf(2500));

        assertNotNull(resolved);
        assertEquals("RELIANCE", resolved.getTradingSymbol());
        assertEquals(12345L, resolved.getInstrumentToken());
        assertEquals(1, resolved.getLotSize());
        assertEquals("EQ", resolved.getInstrumentType());
    }

    @Test
    void testResolveEQ_NotFound() {
        when(instrumentRepository.findAllByTradingsymbolAndExchangeIn("NOTFOUND", new String[]{"BSE", "NSE"}))
                .thenReturn(List.of());

        assertThrows(RuntimeException.class, () ->
                instrumentResolverService.resolve("NOTFOUND", TradingSegment.EQ, TradeDirection.LONG, BigDecimal.valueOf(2500))
        );
    }

    @Test
    void testResolveFUT() {
        LocalDateTime now = LocalDateTime.now();
        Instrument fut1 = Instrument.builder()
                .instrumentToken(100001L)
                .tradingsymbol("RELIANCE24DEC")
                .lotSize(75)
                .instrumentType("FUT")
                .expiry(now.plusDays(30))
                .build();

        Instrument fut2 = Instrument.builder()
                .instrumentToken(100002L)
                .tradingsymbol("RELIANCE24JAN")
                .lotSize(75)
                .instrumentType("FUT")
                .expiry(now.plusDays(60))
                .build();

        when(instrumentRepository.findAllByTradingsymbolStartingWithAndExpiryBetweenAndExchangeIn(
                eq("RELIANCE"), any(), any(), eq(new String[]{"NFO"})))
                .thenReturn(Arrays.asList(fut1, fut2));

        ResolvedInstrument resolved = instrumentResolverService.resolve(
                "RELIANCE", TradingSegment.FUT, TradeDirection.LONG, BigDecimal.valueOf(2500));

        assertNotNull(resolved);
        assertEquals("RELIANCE24DEC", resolved.getTradingSymbol());
        assertEquals(100001L, resolved.getInstrumentToken());
        assertEquals(75, resolved.getLotSize());
        assertEquals("FUT", resolved.getInstrumentType());
    }

    @Test
    void testResolveOPT_LONG() {
        LocalDateTime now = LocalDateTime.now();
        Instrument ce2400 = Instrument.builder()
                .instrumentToken(200001L)
                .tradingsymbol("RELIANCE24JAN2400CE")
                .lotSize(100)
                .instrumentType("CE")
                .expiry(now.plusDays(35))
                .strike("2400")
                .build();

        Instrument ce2500 = Instrument.builder()
                .instrumentToken(200002L)
                .tradingsymbol("RELIANCE24JAN2500CE")
                .lotSize(100)
                .instrumentType("CE")
                .expiry(now.plusDays(35))
                .strike("2500")
                .build();

        Instrument ce2600 = Instrument.builder()
                .instrumentToken(200003L)
                .tradingsymbol("RELIANCE24JAN2600CE")
                .lotSize(100)
                .instrumentType("CE")
                .expiry(now.plusDays(35))
                .strike("2600")
                .build();

        when(instrumentRepository.findAllByTradingsymbolStartingWithAndExpiryBetweenAndExchangeIn(
                eq("RELIANCE"), any(), any(), eq(new String[]{"NFO"})))
                .thenReturn(Arrays.asList(ce2400, ce2500, ce2600));

        ResolvedInstrument resolved = instrumentResolverService.resolve(
                "RELIANCE", TradingSegment.OPT, TradeDirection.LONG, BigDecimal.valueOf(2480));

        assertNotNull(resolved);
        assertEquals("CE", resolved.getInstrumentType());
        assertEquals(BigDecimal.valueOf(2500), resolved.getStrike());
    }

    @Test
    void testResolveOPT_SHORT() {
        LocalDateTime now = LocalDateTime.now();
        Instrument pe2400 = Instrument.builder()
                .instrumentToken(300001L)
                .tradingsymbol("RELIANCE24JAN2400PE")
                .lotSize(100)
                .instrumentType("PE")
                .expiry(now.plusDays(35))
                .strike("2400")
                .build();

        when(instrumentRepository.findAllByTradingsymbolStartingWithAndExpiryBetweenAndExchangeIn(
                eq("RELIANCE"), any(), any(), eq(new String[]{"NFO"})))
                .thenReturn(Arrays.asList(pe2400));

        ResolvedInstrument resolved = instrumentResolverService.resolve(
                "RELIANCE", TradingSegment.OPT, TradeDirection.SHORT, BigDecimal.valueOf(2480));

        assertNotNull(resolved);
        assertEquals("PE", resolved.getInstrumentType());
    }

    @Test
    void testResolveEQ_NotFound_ThrowsRuntimeException() {
        when(instrumentRepository.findAllByTradingsymbolAndExchangeIn("NONEXISTENT", new String[]{"BSE", "NSE"}))
                .thenReturn(List.of());

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                instrumentResolverService.resolve("NONEXISTENT", TradingSegment.EQ, TradeDirection.LONG, BigDecimal.valueOf(2500))
        );

        assertTrue(ex.getMessage().contains("EQ instrument not found"));
    }

    @Test
    void testResolveEQ_BSEPreferredOverNSE() {
        Instrument nseInst = Instrument.builder()
                .instrumentToken(11111L)
                .tradingsymbol("RELIANCE")
                .exchange("NSE")
                .lotSize(1)
                .instrumentType("EQ")
                .build();

        Instrument bseInst = Instrument.builder()
                .instrumentToken(22222L)
                .tradingsymbol("RELIANCE")
                .exchange("BSE")
                .lotSize(1)
                .instrumentType("EQ")
                .build();

        when(instrumentRepository.findAllByTradingsymbolAndExchangeIn("RELIANCE", new String[]{"BSE", "NSE"}))
                .thenReturn(Arrays.asList(nseInst, bseInst));

        ResolvedInstrument resolved = instrumentResolverService.resolve(
                "RELIANCE", TradingSegment.EQ, TradeDirection.LONG, BigDecimal.valueOf(2500));

        assertNotNull(resolved);
        // BSE should be preferred over NSE
        assertEquals(22222L, resolved.getInstrumentToken());
    }

    @Test
    void testResolveFUT_NearestExpirySelected() {
        LocalDateTime now = LocalDateTime.now();
        Instrument fut1 = Instrument.builder()
                .instrumentToken(100001L)
                .tradingsymbol("BANKEX24DEC")
                .lotSize(75)
                .instrumentType("FUT")
                .expiry(now.plusDays(10))
                .build();

        Instrument fut2 = Instrument.builder()
                .instrumentToken(100002L)
                .tradingsymbol("BANKEX24JAN")
                .lotSize(75)
                .instrumentType("FUT")
                .expiry(now.plusDays(40))
                .build();

        Instrument fut3 = Instrument.builder()
                .instrumentToken(100003L)
                .tradingsymbol("BANKEX24FEB")
                .lotSize(75)
                .instrumentType("FUT")
                .expiry(now.plusDays(70))
                .build();

        when(instrumentRepository.findAllByTradingsymbolStartingWithAndExpiryBetweenAndExchangeIn(
                eq("BANKEX"), any(), any(), eq(new String[]{"NFO"})))
                .thenReturn(Arrays.asList(fut2, fut1, fut3));

        ResolvedInstrument resolved = instrumentResolverService.resolve(
                "BANKEX", TradingSegment.FUT, TradeDirection.LONG, BigDecimal.valueOf(5000));

        assertNotNull(resolved);
        // Should select fut1 (earliest expiry at +10 days)
        assertEquals("BANKEX24DEC", resolved.getTradingSymbol());
        assertEquals(100001L, resolved.getInstrumentToken());
    }

    @Test
    void testResolveFUT_NotFound_ThrowsRuntimeException() {
        when(instrumentRepository.findAllByTradingsymbolStartingWithAndExpiryBetweenAndExchangeIn(
                eq("NIFTY_INVALID"), any(), any(), eq(new String[]{"NFO"})))
                .thenReturn(List.of());

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                instrumentResolverService.resolve("NIFTY_INVALID", TradingSegment.FUT, TradeDirection.LONG, BigDecimal.valueOf(20000))
        );

        assertTrue(ex.getMessage().contains("No FUT instrument found"));
    }

    @Test
    void testResolveOPT_CE_ForLONG_PE_ForSHORT() {
        LocalDateTime now = LocalDateTime.now();
        Instrument ce2500 = Instrument.builder()
                .instrumentToken(200001L)
                .tradingsymbol("NIFTY24JAN2500CE")
                .lotSize(100)
                .instrumentType("CE")
                .expiry(now.plusDays(30))
                .strike("2500")
                .build();

        Instrument pe2500 = Instrument.builder()
                .instrumentToken(300001L)
                .tradingsymbol("NIFTY24JAN2500PE")
                .lotSize(100)
                .instrumentType("PE")
                .expiry(now.plusDays(30))
                .strike("2500")
                .build();

        // Test LONG → CE
        when(instrumentRepository.findAllByTradingsymbolStartingWithAndExpiryBetweenAndExchangeIn(
                eq("NIFTY"), any(), any(), eq(new String[]{"NFO"})))
                .thenReturn(Arrays.asList(ce2500, pe2500));

        ResolvedInstrument resolvedLong = instrumentResolverService.resolve(
                "NIFTY", TradingSegment.OPT, TradeDirection.LONG, BigDecimal.valueOf(2480));

        assertNotNull(resolvedLong);
        assertEquals("CE", resolvedLong.getInstrumentType());

        // Test SHORT → PE
        ResolvedInstrument resolvedShort = instrumentResolverService.resolve(
                "NIFTY", TradingSegment.OPT, TradeDirection.SHORT, BigDecimal.valueOf(2480));

        assertNotNull(resolvedShort);
        assertEquals("PE", resolvedShort.getInstrumentType());
    }

    @Test
    void testResolveOPT_StrikeSelection_NearestToTarget() {
        LocalDateTime now = LocalDateTime.now();
        Instrument ce2400 = Instrument.builder()
                .instrumentToken(200001L)
                .tradingsymbol("SENSEX24JAN2400CE")
                .lotSize(100)
                .instrumentType("CE")
                .expiry(now.plusDays(30))
                .strike("2400")
                .build();

        Instrument ce2450 = Instrument.builder()
                .instrumentToken(200002L)
                .tradingsymbol("SENSEX24JAN2450CE")
                .lotSize(100)
                .instrumentType("CE")
                .expiry(now.plusDays(30))
                .strike("2450")
                .build();

        Instrument ce2500 = Instrument.builder()
                .instrumentToken(200003L)
                .tradingsymbol("SENSEX24JAN2500CE")
                .lotSize(100)
                .instrumentType("CE")
                .expiry(now.plusDays(30))
                .strike("2500")
                .build();

        Instrument ce2550 = Instrument.builder()
                .instrumentToken(200004L)
                .tradingsymbol("SENSEX24JAN2550CE")
                .lotSize(100)
                .instrumentType("CE")
                .expiry(now.plusDays(30))
                .strike("2550")
                .build();

        when(instrumentRepository.findAllByTradingsymbolStartingWithAndExpiryBetweenAndExchangeIn(
                eq("SENSEX"), any(), any(), eq(new String[]{"NFO"})))
                .thenReturn(Arrays.asList(ce2400, ce2450, ce2500, ce2550));

        // Target price at 2475 — should select ce2500 (distance 25) over ce2450 (distance 25) or ce2400 (distance 75) or ce2550 (distance 75)
        // Actually it will be ce2450 or ce2500 as they have same distance, but the algorithm picks nearest
        ResolvedInstrument resolved = instrumentResolverService.resolve(
                "SENSEX", TradingSegment.OPT, TradeDirection.LONG, BigDecimal.valueOf(2475));

        assertNotNull(resolved);
        assertEquals("CE", resolved.getInstrumentType());
        // Should be one of the nearest strikes
        assertTrue(resolved.getStrike().equals(BigDecimal.valueOf(2450)) ||
                  resolved.getStrike().equals(BigDecimal.valueOf(2500)));
    }
}
