package com.dtech.aitrader.v2.rules.patterns.dataload;

import com.dtech.aitrader.v2.rules.SymbolContext;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.market.fetch.DataFetchException;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.PivotType;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.StructureLabel;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * PatternContextAttacher — locks the separate-compute / shared-storage contract per owner
 * correction ({@code 89a52589}): pattern data path is independent code, augments the shared
 * {@link SymbolContext} so EW + pattern firings co-exist in one engine pass.
 */
class PatternContextAttacherTest {

    @Test
    void attaches_series_and_pivots_for_matching_tf() {
        InstrumentRepository instrumentRepo = Mockito.mock(InstrumentRepository.class);
        BarsLoader barsLoader = Mockito.mock(BarsLoader.class);
        Instrument instr = new Instrument();
        when(instrumentRepo.findByTradingsymbolAndExchangeIn(eq("RELIANCE"), any()))
                .thenReturn(instr);
        BarSeries fakeSeries = buildFakeSeries(50);
        try {
            when(barsLoader.loadInstrumentSeries(any(), any())).thenReturn(fakeSeries);
        } catch (DataFetchException e) {
            fail("mock setup failed: " + e.getMessage());
        }

        SymbolContext base = baseCtx("RELIANCE", "Week",
                Map.of("Day", List.of(pivot("2026-04-29T09:45:00Z", 1318.7, PivotType.LOW))));

        PatternContextAttacher attacher = new PatternContextAttacher(instrumentRepo, barsLoader, new com.dtech.aitrader.v2.rules.patterns.dataload.CandleSwingExtractor());
        SymbolContext out = attacher.attach(base, "Day");

        assertNotNull(out.getSeries(), "series should be populated");
        assertEquals(50, out.getSeries().getBarCount());
        assertEquals(1, out.getPivots().size());
        assertEquals(1318.7, out.getPivots().get(0).getPrice(), 1e-9);
        // Base EW fields preserved.
        assertEquals("RELIANCE", out.getSymbol());
        assertEquals("Week", out.getTf());
        assertEquals(base.getPivotsByTf(), out.getPivotsByTf());
    }

    @Test
    void returns_base_unchanged_when_instrument_lookup_fails() {
        InstrumentRepository instrumentRepo = Mockito.mock(InstrumentRepository.class);
        BarsLoader barsLoader = Mockito.mock(BarsLoader.class);
        when(instrumentRepo.findByTradingsymbolAndExchangeIn(any(), any())).thenReturn(null);

        SymbolContext base = baseCtx("UNKNOWN", "Week", Map.of());
        PatternContextAttacher attacher = new PatternContextAttacher(instrumentRepo, barsLoader, new com.dtech.aitrader.v2.rules.patterns.dataload.CandleSwingExtractor());
        SymbolContext out = attacher.attach(base, "Day");

        assertSame(base, out, "unknown instrument ⇒ return base unchanged");
        assertNull(out.getSeries());
    }

    @Test
    void returns_base_unchanged_on_unknown_tf_label() {
        InstrumentRepository instrumentRepo = Mockito.mock(InstrumentRepository.class);
        BarsLoader barsLoader = Mockito.mock(BarsLoader.class);
        SymbolContext base = baseCtx("RELIANCE", "Week", Map.of());

        PatternContextAttacher attacher = new PatternContextAttacher(instrumentRepo, barsLoader, new com.dtech.aitrader.v2.rules.patterns.dataload.CandleSwingExtractor());
        SymbolContext out = attacher.attach(base, "NotARealTf");
        assertSame(base, out);
    }

    @Test
    void empty_pivots_when_target_tf_absent_from_bundle() {
        // Bundle has only Week pivots; pattern asks for Day → empty pivot list (series still set).
        InstrumentRepository instrumentRepo = Mockito.mock(InstrumentRepository.class);
        BarsLoader barsLoader = Mockito.mock(BarsLoader.class);
        Instrument instr = new Instrument();
        when(instrumentRepo.findByTradingsymbolAndExchangeIn(any(), any())).thenReturn(instr);
        BarSeries fakeSeries = buildFakeSeries(30);
        try {
            when(barsLoader.loadInstrumentSeries(any(), any())).thenReturn(fakeSeries);
        } catch (DataFetchException e) {
            fail();
        }

        SymbolContext base = baseCtx("RELIANCE", "Week",
                Map.of("Week", List.of(pivot("2026-04-01T00:00:00Z", 1500.0, PivotType.HIGH))));

        PatternContextAttacher attacher = new PatternContextAttacher(instrumentRepo, barsLoader, new com.dtech.aitrader.v2.rules.patterns.dataload.CandleSwingExtractor());
        SymbolContext out = attacher.attach(base, "Day");

        assertNotNull(out.getSeries(), "series populated even if no Day pivots in bundle");
        assertTrue(out.getPivots().isEmpty(),
                "Day pivots absent ⇒ empty pivot list (pattern rules will no-op)");
    }

    // ── fixture helpers ────────────────────────────────────────────────────────

    private static SymbolContext baseCtx(String symbol, String tf,
                                            Map<String, List<MarketStructurePoint>> pivotsByTf) {
        Map<String, List<MarketStructurePoint>> safe = new LinkedHashMap<>(pivotsByTf);
        return SymbolContext.builder()
                .symbol(symbol).tf(tf)
                .asOf(LocalDate.of(2026, 5, 25))
                .pivotsByTf(safe)
                .pivots(safe.getOrDefault(tf, List.of()))
                .build();
    }

    private static MarketStructurePoint pivot(String iso, double price, PivotType k) {
        return MarketStructurePoint.builder()
                .pivotType(k).structureLabel(StructureLabel.FIRST)
                .timestamp(Instant.parse(iso)).price(price)
                .atrAtPivot(10.0).rsiAtPivot(50.0).build();
    }

    private static BarSeries buildFakeSeries(int bars) {
        BarSeries s = new BaseBarSeriesBuilder().withName("fake").build();
        Instant t0 = Instant.parse("2024-01-01T05:30:00Z");
        for (int i = 0; i < bars; i++) {
            double c = 1300.0 + i * 0.5;
            s.addBar(com.dtech.kitecon.strategy.dataloader.BarsLoader.getBar(
                    c, c + 5, c - 5, c, 1_000.0,
                    t0.plus(Duration.ofHours(i + 1)), Duration.ofHours(1)));
        }
        return s;
    }
}
