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
 * RectangleDetectRule — locks SPEC-008 rectangle geometry: BOTH lines flat + meaningful range
 * height (≥1.5 ATR). Distinguishes from channel (both sloped), triangle (one flat one sloped),
 * wedge (converging).
 */
class RectangleDetectRuleTest {

    private final RectangleDetectRule rule = new RectangleDetectRule();

    private static Firing latest(List<Firing> out) {
        assertFalse(out.isEmpty(), "expected at least one firing");
        Firing best = out.get(0);
        int bestSpanEnd = ((Number) best.getPayload().get("span_end_idx")).intValue();
        for (Firing f : out) {
            int spanEnd = ((Number) f.getPayload().get("span_end_idx")).intValue();
            if (spanEnd > bestSpanEnd) { best = f; bestSpanEnd = spanEnd; }
        }
        return best;
    }

    @Test
    void rectangle_detected_when_both_lines_flat_and_height_meaningful() {
        // Highs ~1450 (slight noise), lows ~1300 (slight noise) — both flat, height ~150.
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1300.0, PivotType.LOW),
                        new Pivot(15, 1450.0, PivotType.HIGH),
                        new Pivot(20, 1302.0, PivotType.LOW),
                        new Pivot(25, 1448.0, PivotType.HIGH),
                        new Pivot(30, 1301.0, PivotType.LOW),
                        new Pivot(35, 1452.0, PivotType.HIGH),
                        new Pivot(40, 1300.0, PivotType.LOW),
                        new Pivot(45, 1449.0, PivotType.HIGH)),
                /*closeAtEnd=*/ 1380.0,
                /*closePrev=*/ 1370.0);

        List<Firing> out = rule.evaluate(ctx, List.of());
        Map<String, Object> p = latest(out).getPayload();
        assertEquals("inside", p.get("range_state"));
        assertEquals("NEUTRAL", p.get("bias"));
    }

    @Test
    void rectangle_confirmed_on_break_above_upper() {
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1300.0, PivotType.LOW),
                        new Pivot(15, 1450.0, PivotType.HIGH),
                        new Pivot(20, 1302.0, PivotType.LOW),
                        new Pivot(25, 1448.0, PivotType.HIGH),
                        new Pivot(30, 1301.0, PivotType.LOW),
                        new Pivot(35, 1452.0, PivotType.HIGH),
                        new Pivot(40, 1300.0, PivotType.LOW),
                        new Pivot(45, 1449.0, PivotType.HIGH)),
                /*closeAtEnd=*/ 1500.0,    // breaks above ~1450
                /*closePrev=*/ 1440.0);

        Firing f = latest(rule.evaluate(ctx, List.of()));
        Map<String, Object> p = f.getPayload();
        assertEquals("confirmed", p.get("status"));
        assertEquals("broken_up", p.get("range_state"));
        assertEquals("LONG", p.get("bias"));
    }

    @Test
    void rectangle_confirmed_on_break_below_lower() {
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1300.0, PivotType.LOW),
                        new Pivot(15, 1450.0, PivotType.HIGH),
                        new Pivot(20, 1302.0, PivotType.LOW),
                        new Pivot(25, 1448.0, PivotType.HIGH),
                        new Pivot(30, 1301.0, PivotType.LOW),
                        new Pivot(35, 1452.0, PivotType.HIGH),
                        new Pivot(40, 1300.0, PivotType.LOW),
                        new Pivot(45, 1449.0, PivotType.HIGH)),
                /*closeAtEnd=*/ 1250.0,    // breaks below ~1300
                /*closePrev=*/ 1310.0);

        Firing f = latest(rule.evaluate(ctx, List.of()));
        Map<String, Object> p = f.getPayload();
        assertEquals("confirmed", p.get("status"));
        assertEquals("broken_down", p.get("range_state"));
        assertEquals("SHORT", p.get("bias"));
    }

    @Test
    void sloped_channel_not_a_rectangle() {
        // Both lines rising — channel, not rectangle (neither flat).
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1300.0, PivotType.LOW),
                        new Pivot(15, 1400.0, PivotType.HIGH),
                        new Pivot(20, 1330.0, PivotType.LOW),
                        new Pivot(25, 1430.0, PivotType.HIGH),
                        new Pivot(30, 1360.0, PivotType.LOW),
                        new Pivot(35, 1460.0, PivotType.HIGH)),
                1420.0, 1410.0);
        assertTrue(rule.evaluate(ctx, List.of()).isEmpty(),
                "rising-parallel channel must not classify as rectangle");
    }

    @Test
    void ascending_triangle_not_a_rectangle() {
        // Flat highs ~1450, rising lows — triangle (only upper is flat).
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1300.0, PivotType.LOW),
                        new Pivot(15, 1450.0, PivotType.HIGH),
                        new Pivot(20, 1330.0, PivotType.LOW),
                        new Pivot(25, 1450.0, PivotType.HIGH),
                        new Pivot(30, 1360.0, PivotType.LOW),
                        new Pivot(35, 1452.0, PivotType.HIGH),
                        new Pivot(40, 1390.0, PivotType.LOW),
                        new Pivot(45, 1448.0, PivotType.HIGH)),
                1410.0, 1408.0);
        assertTrue(rule.evaluate(ctx, List.of()).isEmpty(),
                "ascending triangle (one flat, one sloped) must not be a rectangle");
    }

    @Test
    void narrow_range_below_min_height_rejected() {
        // Both flat but range only ~20 (well below 1.5 * ATR_30 = 45).
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
                "narrow range (< 1.5 ATR) must not classify as rectangle");
    }

    @Test
    void target_projection_equals_height_on_break_up() {
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1300.0, PivotType.LOW),
                        new Pivot(15, 1450.0, PivotType.HIGH),
                        new Pivot(20, 1302.0, PivotType.LOW),
                        new Pivot(25, 1448.0, PivotType.HIGH),
                        new Pivot(30, 1301.0, PivotType.LOW),
                        new Pivot(35, 1452.0, PivotType.HIGH),
                        new Pivot(40, 1300.0, PivotType.LOW),
                        new Pivot(45, 1449.0, PivotType.HIGH)),
                1500.0, 1440.0);
        Firing f = latest(rule.evaluate(ctx, List.of()));
        Map<String, Object> p = f.getPayload();
        double upper = ((Number) p.get("upper_line_at_now")).doubleValue();
        double target = ((Number) p.get("target_price")).doubleValue();
        double height = ((Number) p.get("rect_height")).doubleValue();
        assertEquals(upper + height, target, 1e-6,
                "broken_up target must equal upper line + range height");
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
        BarSeries s = new BaseBarSeriesBuilder().withName("rect").build();
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
