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
                .lotSize(1)
                .instrumentType("EQ")
                .build();

        when(instrumentRepository.findAllByTradingsymbolAndExchangeIn("RELIANCE", new String[]{"NSE"}))
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
        when(instrumentRepository.findAllByTradingsymbolAndExchangeIn("NOTFOUND", new String[]{"NSE"}))
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
}
