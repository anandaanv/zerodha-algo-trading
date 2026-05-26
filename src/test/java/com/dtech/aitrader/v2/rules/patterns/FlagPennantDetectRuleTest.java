package com.dtech.aitrader.v2.rules.patterns;

import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.SymbolContext;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.PivotType;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.StructureLabel;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FlagPennantDetectRule — locks SPEC-008 flag/pennant geometry: a sharp pole + counter-trend
 * consolidation. Flag = parallel counter-sloped lines. Pennant = converging counter-sloped lines.
 * Zigzag-substrate caveat acknowledged; pole detection is approximate on pivots.
 */
class FlagPennantDetectRuleTest {

    private final FlagPennantDetectRule rule = new FlagPennantDetectRule();

    @Test
    void bull_flag_detected_with_up_pole_and_down_sloped_parallel_consolidation() {
        // Pole: 1200 → 1450 over 8 bars (+20.8%).
        // Consolidation: counter-sloped (down) parallel lines.
        // At endIdx=59 (14 bars past last pivot): upper extrapolates ~1406, lower ~1371.
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(5, 1200.0, PivotType.LOW),    // pole start
                        new Pivot(13, 1450.0, PivotType.HIGH),  // pole end
                        new Pivot(20, 1410.0, PivotType.LOW),
                        new Pivot(25, 1440.0, PivotType.HIGH),
                        new Pivot(30, 1400.0, PivotType.LOW),
                        new Pivot(35, 1430.0, PivotType.HIGH),
                        new Pivot(40, 1390.0, PivotType.LOW),
                        new Pivot(45, 1420.0, PivotType.HIGH)),
                /*closeAtEnd=*/ 1390.0,
                /*closePrev=*/ 1385.0);

        List<Firing> out = rule.evaluate(ctx, List.of());
        assertEquals(1, out.size());
        Map<String, Object> p = out.get(0).getPayload();
        assertEquals("up", p.get("pole_direction"));
        assertEquals("LONG", p.get("bias"));
        assertEquals("flag", p.get("pattern_type"));
        assertEquals("inside", p.get("consolidation_state"));
    }

    @Test
    void bear_flag_detected_with_down_pole_and_up_sloped_parallel_consolidation() {
        // At endIdx=59: upper ~1279, lower ~1244 — close inside at 1260.
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(5, 1500.0, PivotType.HIGH),   // pole start
                        new Pivot(13, 1200.0, PivotType.LOW),   // pole end (-20%)
                        new Pivot(20, 1240.0, PivotType.HIGH),
                        new Pivot(25, 1210.0, PivotType.LOW),
                        new Pivot(30, 1250.0, PivotType.HIGH),
                        new Pivot(35, 1220.0, PivotType.LOW),
                        new Pivot(40, 1260.0, PivotType.HIGH),
                        new Pivot(45, 1230.0, PivotType.LOW)),
                /*closeAtEnd=*/ 1260.0,
                /*closePrev=*/ 1262.0);

        Firing f = rule.evaluate(ctx, List.of()).get(0);
        Map<String, Object> p = f.getPayload();
        assertEquals("down", p.get("pole_direction"));
        assertEquals("SHORT", p.get("bias"));
        assertEquals("flag", p.get("pattern_type"));
    }

    @Test
    void bull_pennant_detected_when_consolidation_converges() {
        // Symmetric triangle after up-pole: upper falls (-0.8/bar), lower rises (+0.5/bar).
        // Lines don't cross until well past endIdx.
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(5, 1200.0, PivotType.LOW),
                        new Pivot(13, 1465.0, PivotType.HIGH),
                        new Pivot(20, 1390.0, PivotType.LOW),
                        new Pivot(25, 1465.0, PivotType.HIGH),
                        new Pivot(30, 1395.0, PivotType.LOW),
                        new Pivot(35, 1457.0, PivotType.HIGH),
                        new Pivot(40, 1400.0, PivotType.LOW),
                        new Pivot(45, 1449.0, PivotType.HIGH)),
                1420.0, 1418.0);

        Firing f = rule.evaluate(ctx, List.of()).get(0);
        Map<String, Object> p = f.getPayload();
        assertEquals("up", p.get("pole_direction"));
        assertEquals("pennant", p.get("pattern_type"));
    }

    @Test
    void bull_flag_confirmed_on_break_above_upper() {
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(5, 1200.0, PivotType.LOW),
                        new Pivot(13, 1450.0, PivotType.HIGH),
                        new Pivot(20, 1410.0, PivotType.LOW),
                        new Pivot(25, 1440.0, PivotType.HIGH),
                        new Pivot(30, 1400.0, PivotType.LOW),
                        new Pivot(35, 1430.0, PivotType.HIGH),
                        new Pivot(40, 1390.0, PivotType.LOW),
                        new Pivot(45, 1420.0, PivotType.HIGH)),
                /*closeAtEnd=*/ 1450.0,    // breaks above extrapolated upper line
                /*closePrev=*/ 1410.0);

        Firing f = rule.evaluate(ctx, List.of()).get(0);
        Map<String, Object> p = f.getPayload();
        assertEquals("confirmed", p.get("status"));
        assertEquals("broken_up", p.get("consolidation_state"));
        assertEquals("LONG", p.get("bias"));
    }

    @Test
    void no_pole_no_pattern() {
        // No large directional move — just a tight range.
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1390.0, PivotType.LOW),
                        new Pivot(15, 1410.0, PivotType.HIGH),
                        new Pivot(20, 1391.0, PivotType.LOW),
                        new Pivot(25, 1409.0, PivotType.HIGH),
                        new Pivot(30, 1390.0, PivotType.LOW),
                        new Pivot(35, 1411.0, PivotType.HIGH),
                        new Pivot(40, 1389.0, PivotType.LOW),
                        new Pivot(45, 1410.0, PivotType.HIGH)),
                1400.0, 1400.0);
        assertTrue(rule.evaluate(ctx, List.of()).isEmpty(),
                "no pole present — must not emit a flag/pennant");
    }

    @Test
    void consolidation_same_direction_as_pole_rejected() {
        // Pole up, then consolidation also rising (continuation channel, NOT a flag).
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(5, 1200.0, PivotType.LOW),
                        new Pivot(13, 1450.0, PivotType.HIGH),
                        new Pivot(20, 1430.0, PivotType.LOW),
                        new Pivot(25, 1460.0, PivotType.HIGH),
                        new Pivot(30, 1440.0, PivotType.LOW),
                        new Pivot(35, 1470.0, PivotType.HIGH),
                        new Pivot(40, 1450.0, PivotType.LOW),
                        new Pivot(45, 1480.0, PivotType.HIGH)),
                1465.0, 1460.0);
        assertTrue(rule.evaluate(ctx, List.of()).isEmpty(),
                "consolidation in pole direction must not classify as flag/pennant");
    }

    @Test
    void target_projection_equals_pole_height_on_break_up() {
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(5, 1200.0, PivotType.LOW),
                        new Pivot(13, 1450.0, PivotType.HIGH),
                        new Pivot(20, 1410.0, PivotType.LOW),
                        new Pivot(25, 1440.0, PivotType.HIGH),
                        new Pivot(30, 1400.0, PivotType.LOW),
                        new Pivot(35, 1430.0, PivotType.HIGH),
                        new Pivot(40, 1390.0, PivotType.LOW),
                        new Pivot(45, 1420.0, PivotType.HIGH)),
                1450.0, 1410.0);
        Firing f = rule.evaluate(ctx, List.of()).get(0);
        Map<String, Object> p = f.getPayload();
        double upper = ((Number) p.get("upper_line_at_now")).doubleValue();
        double target = ((Number) p.get("target_price")).doubleValue();
        double poleHeight = ((Number) p.get("pole_height")).doubleValue();
        assertEquals(250.0, poleHeight, 1e-6);
        assertEquals(upper + poleHeight, target, 1e-6,
                "bull-flag target = upper line + pole height");
    }

    // ── fixture helpers ────────────────────────────────────────────────────────

    private record Pivot(int barIdx, double price, PivotType type) { }

    private static SymbolContext ctxWithPattern(List<Pivot> pivots, double closeAtEnd,
                                                   double closePrev) {
        BarSeries series = buildBars(60, closeAtEnd, closePrev, pivots);
        List<MarketStructurePoint> mspList = new ArrayList<>(pivots.size());
        for (Pivot pv : pivots) {
            Instant ts = series.getBar(pv.barIdx).getEndTime();
            mspList.add(MarketStructurePoint.builder()
                    .pivotType(pv.type)
                    .structureLabel(StructureLabel.FIRST)
                    .timestamp(ts).price(pv.price)
                    .atrAtPivot(30.0).rsiAtPivot(50.0).build());
        }
        return SymbolContext.builder()
                .symbol("TEST").tf("OneHour").asOf(LocalDate.of(2026, 5, 26))
                .series(series).pivots(mspList).build();
    }

    private static BarSeries buildBars(int barCount, double closeAtEnd, double closePrev,
                                          List<Pivot> pivots) {
        BarSeries s = new BaseBarSeriesBuilder().withName("flag").build();
        Instant t0 = Instant.parse("2024-01-01T05:30:00Z");
        for (int i = 0; i < barCount; i++) {
            double c;
            if (i == barCount - 1) c = closeAtEnd;
            else if (i == barCount - 2) c = closePrev;
            else c = 1400.0;
            double o = c;
            double h = c + 15.0;
            double l = c - 15.0;
            for (Pivot pv : pivots) {
                if (pv.barIdx == i) {
                    if (pv.type == PivotType.HIGH) {
                        h = Math.max(h, pv.price);
                        c = pv.price - 1.0;
                    } else {
                        l = Math.min(l, pv.price);
                        c = pv.price + 1.0;
                    }
                }
            }
            s.addBar(BarsLoader.getBar(o, h, l, c, 1_000.0,
                    t0.plus(Duration.ofHours(i + 1)), Duration.ofHours(1)));
        }
        return s;
    }
}
