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
 * WedgeDetectRule — locks SPEC-008 wedge geometry: BOTH lines trending same direction +
 * meaningful convergence. Distinguishes from triangle (≥1 line flat) and channel (parallel).
 */
class WedgeDetectRuleTest {

    private final WedgeDetectRule rule = new WedgeDetectRule();

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
    void rising_wedge_detected_when_both_rising_lower_faster_converging() {
        // Highs rising slowly: 1400→1408→1416→1424 (slope +0.8/bar)
        // Lows rising faster: 1300→1325→1350→1375 (slope +2.5/bar) — converging from ~100 → ~38
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1300.0, PivotType.LOW),
                        new Pivot(15, 1400.0, PivotType.HIGH),
                        new Pivot(20, 1325.0, PivotType.LOW),
                        new Pivot(25, 1408.0, PivotType.HIGH),
                        new Pivot(30, 1350.0, PivotType.LOW),
                        new Pivot(35, 1416.0, PivotType.HIGH),
                        new Pivot(40, 1375.0, PivotType.LOW),
                        new Pivot(45, 1424.0, PivotType.HIGH)),
                /*closeAtEnd=*/ 1418.0,
                /*closePrev=*/ 1415.0);

        List<Firing> out = rule.evaluate(ctx, List.of());
        Map<String, Object> p = latest(out).getPayload();
        assertEquals("rising", p.get("wedge_type"));
        assertEquals("SHORT", p.get("bias"));
    }

    @Test
    void falling_wedge_detected_when_both_falling_upper_faster_converging() {
        // Highs falling fast: 1500→1475→1450→1425 (slope -2.5/bar)
        // Lows falling slow: 1400→1395→1390→1385 (slope -0.5/bar) — converging
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1500.0, PivotType.HIGH),
                        new Pivot(15, 1400.0, PivotType.LOW),
                        new Pivot(20, 1475.0, PivotType.HIGH),
                        new Pivot(25, 1395.0, PivotType.LOW),
                        new Pivot(30, 1450.0, PivotType.HIGH),
                        new Pivot(35, 1390.0, PivotType.LOW),
                        new Pivot(40, 1425.0, PivotType.HIGH),
                        new Pivot(45, 1385.0, PivotType.LOW)),
                /*closeAtEnd=*/ 1400.0,
                /*closePrev=*/ 1395.0);

        Firing f = latest(rule.evaluate(ctx, List.of()));
        Map<String, Object> p = f.getPayload();
        assertEquals("falling", p.get("wedge_type"));
        assertEquals("LONG", p.get("bias"));
    }

    @Test
    void rising_wedge_confirmed_on_breakdown_below_lower() {
        // Same as rising-wedge fixture but close NOW breaks below lower line.
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1300.0, PivotType.LOW),
                        new Pivot(15, 1400.0, PivotType.HIGH),
                        new Pivot(20, 1325.0, PivotType.LOW),
                        new Pivot(25, 1408.0, PivotType.HIGH),
                        new Pivot(30, 1350.0, PivotType.LOW),
                        new Pivot(35, 1416.0, PivotType.HIGH),
                        new Pivot(40, 1375.0, PivotType.LOW),
                        new Pivot(45, 1424.0, PivotType.HIGH)),
                /*closeAtEnd=*/ 1370.0,    // breaks below extrapolated lower line ≈ 1410
                /*closePrev=*/ 1418.0);

        Firing f = latest(rule.evaluate(ctx, List.of()));
        Map<String, Object> p = f.getPayload();
        assertEquals("confirmed", p.get("status"));
        assertEquals("below_lower", p.get("confirmed_direction"));
        assertEquals(100.0, ((Number) p.get("completion_pct")).doubleValue(), 1e-9);
    }

    @Test
    void diverging_megaphone_not_a_wedge() {
        // Upper rising + lower falling — that's broadening, not a wedge.
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1400.0, PivotType.HIGH),
                        new Pivot(15, 1380.0, PivotType.LOW),
                        new Pivot(20, 1420.0, PivotType.HIGH),
                        new Pivot(25, 1360.0, PivotType.LOW),
                        new Pivot(30, 1450.0, PivotType.HIGH),
                        new Pivot(35, 1340.0, PivotType.LOW),
                        new Pivot(40, 1480.0, PivotType.HIGH),
                        new Pivot(45, 1320.0, PivotType.LOW)),
                1400.0, 1400.0);
        assertTrue(rule.evaluate(ctx, List.of()).isEmpty());
    }

    @Test
    void parallel_channel_not_a_wedge() {
        // Both rising at SAME slope (parallel) — channel, not wedge.
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1300.0, PivotType.LOW),
                        new Pivot(15, 1400.0, PivotType.HIGH),
                        new Pivot(20, 1330.0, PivotType.LOW),
                        new Pivot(25, 1430.0, PivotType.HIGH),
                        new Pivot(30, 1360.0, PivotType.LOW),
                        new Pivot(35, 1460.0, PivotType.HIGH),
                        new Pivot(40, 1390.0, PivotType.LOW),
                        new Pivot(45, 1490.0, PivotType.HIGH)),
                1450.0, 1440.0);
        assertTrue(rule.evaluate(ctx, List.of()).isEmpty(),
                "parallel-rising channel must not classify as wedge");
    }

    @Test
    void rising_lines_but_not_converging_rejected() {
        // Both rising and parallel-ish — not converging enough to be a wedge.
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1300.0, PivotType.LOW),
                        new Pivot(15, 1400.0, PivotType.HIGH),
                        new Pivot(20, 1310.0, PivotType.LOW),
                        new Pivot(25, 1410.0, PivotType.HIGH),
                        new Pivot(30, 1320.0, PivotType.LOW),
                        new Pivot(35, 1420.0, PivotType.HIGH),
                        new Pivot(40, 1330.0, PivotType.LOW),
                        new Pivot(45, 1430.0, PivotType.HIGH)),
                1400.0, 1400.0);
        assertTrue(rule.evaluate(ctx, List.of()).isEmpty(),
                "rising-parallel (insufficient convergence) must not be a wedge");
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
        BarSeries s = new BaseBarSeriesBuilder().withName("wedge").build();
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
