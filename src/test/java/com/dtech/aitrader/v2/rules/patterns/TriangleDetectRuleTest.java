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
 * TriangleDetectRule — locks SPEC-008 ({@code e332be7f}) triangle classification:
 * ascending (flat upper + rising lower → LONG bias), descending (falling upper + flat lower →
 * SHORT bias), symmetrical (falling upper + rising lower → NEUTRAL until break).
 *
 * <p>Fixtures use synthetic pivots designed to produce the target slope/flat geometry on a
 * series with ATR ≈ 30. The completion is continuous; assertions check shape + bias, not
 * exact completion values.
 */
class TriangleDetectRuleTest {

    private final TriangleDetectRule rule = new TriangleDetectRule();

    @Test
    void ascending_triangle_detected_when_upper_flat_lower_rising() {
        // Upper line: highs ~1500 (flat). Lower line: rising lows 1300→1340→1380→1420.
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1300.0, PivotType.LOW),
                        new Pivot(15, 1502.0, PivotType.HIGH),
                        new Pivot(20, 1340.0, PivotType.LOW),
                        new Pivot(25, 1498.0, PivotType.HIGH),
                        new Pivot(30, 1380.0, PivotType.LOW),
                        new Pivot(35, 1501.0, PivotType.HIGH),
                        new Pivot(40, 1420.0, PivotType.LOW),
                        new Pivot(45, 1500.0, PivotType.HIGH)),
                /*closeAtEnd=*/ 1470.0,    // inside the triangle, near upper
                /*closePrev=*/ 1465.0);

        List<Firing> out = rule.evaluate(ctx, List.of());
        assertEquals(1, out.size());
        Map<String, Object> p = out.get(0).getPayload();
        assertEquals("ascending", p.get("triangle_type"));
        assertEquals("LONG", p.get("bias"));
        assertEquals("forming", p.get("status"));
        double upperAtNow = ((Number) p.get("upper_line_at_now")).doubleValue();
        double triggerPrice = ((Number) p.get("trigger_price")).doubleValue();
        assertEquals(upperAtNow, triggerPrice, 1e-6,
                "trigger for ascending = upper line at now");
    }

    @Test
    void descending_triangle_detected_when_upper_falling_lower_flat() {
        // Upper line: falling highs 1500→1460→1420→1380. Lower line: lows ~1300 (flat).
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1500.0, PivotType.HIGH),
                        new Pivot(15, 1300.0, PivotType.LOW),
                        new Pivot(20, 1460.0, PivotType.HIGH),
                        new Pivot(25, 1302.0, PivotType.LOW),
                        new Pivot(30, 1420.0, PivotType.HIGH),
                        new Pivot(35, 1298.0, PivotType.LOW),
                        new Pivot(40, 1380.0, PivotType.HIGH),
                        new Pivot(45, 1301.0, PivotType.LOW)),
                /*closeAtEnd=*/ 1320.0,
                /*closePrev=*/ 1330.0);

        Firing f = rule.evaluate(ctx, List.of()).get(0);
        Map<String, Object> p = f.getPayload();
        assertEquals("descending", p.get("triangle_type"));
        assertEquals("SHORT", p.get("bias"));
    }

    @Test
    void symmetrical_triangle_detected_when_upper_falling_lower_rising() {
        // Converging: highs 1500→1480→1460→1440 ; lows 1300→1320→1340→1360
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1500.0, PivotType.HIGH),
                        new Pivot(15, 1300.0, PivotType.LOW),
                        new Pivot(20, 1480.0, PivotType.HIGH),
                        new Pivot(25, 1320.0, PivotType.LOW),
                        new Pivot(30, 1460.0, PivotType.HIGH),
                        new Pivot(35, 1340.0, PivotType.LOW),
                        new Pivot(40, 1440.0, PivotType.HIGH),
                        new Pivot(45, 1360.0, PivotType.LOW)),
                /*closeAtEnd=*/ 1400.0,
                /*closePrev=*/ 1395.0);

        Firing f = rule.evaluate(ctx, List.of()).get(0);
        Map<String, Object> p = f.getPayload();
        assertEquals("symmetrical", p.get("triangle_type"));
        assertEquals("NEUTRAL", p.get("bias"));
    }

    @Test
    void confirmed_when_close_breaks_upper_ascending() {
        // Same ascending fixture but close NOW pierces 1500.
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1300.0, PivotType.LOW),
                        new Pivot(15, 1502.0, PivotType.HIGH),
                        new Pivot(20, 1340.0, PivotType.LOW),
                        new Pivot(25, 1498.0, PivotType.HIGH),
                        new Pivot(30, 1380.0, PivotType.LOW),
                        new Pivot(35, 1501.0, PivotType.HIGH),
                        new Pivot(40, 1420.0, PivotType.LOW),
                        new Pivot(45, 1500.0, PivotType.HIGH)),
                /*closeAtEnd=*/ 1520.0,   // above the upper line
                /*closePrev=*/ 1499.0);

        Firing f = rule.evaluate(ctx, List.of()).get(0);
        Map<String, Object> p = f.getPayload();
        assertEquals("confirmed", p.get("status"));
        assertEquals(100.0, ((Number) p.get("completion_pct")).doubleValue(), 1e-9);
        assertEquals("above_upper", p.get("confirmed_direction"));
    }

    @Test
    void symmetrical_breakdown_sets_bias_short() {
        // Symmetrical fixture, breakout DOWN through the lower line.
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1500.0, PivotType.HIGH),
                        new Pivot(15, 1300.0, PivotType.LOW),
                        new Pivot(20, 1480.0, PivotType.HIGH),
                        new Pivot(25, 1320.0, PivotType.LOW),
                        new Pivot(30, 1460.0, PivotType.HIGH),
                        new Pivot(35, 1340.0, PivotType.LOW),
                        new Pivot(40, 1440.0, PivotType.HIGH),
                        new Pivot(45, 1360.0, PivotType.LOW)),
                /*closeAtEnd=*/ 1340.0,   // below lower line at endIdx (line ~1370)
                /*closePrev=*/ 1380.0);

        Firing f = rule.evaluate(ctx, List.of()).get(0);
        Map<String, Object> p = f.getPayload();
        assertEquals("symmetrical", p.get("triangle_type"));
        // Symmetrical-on-break: bias becomes break direction
        assertEquals("SHORT", p.get("bias"));
        assertEquals("confirmed", p.get("status"));
        assertEquals("below_lower", p.get("confirmed_direction"));
    }

    @Test
    void diverging_megaphone_not_a_triangle() {
        // Upper rising + lower falling — broadening / megaphone, NOT classical triangle.
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1400.0, PivotType.HIGH),
                        new Pivot(15, 1380.0, PivotType.LOW),
                        new Pivot(20, 1420.0, PivotType.HIGH),
                        new Pivot(25, 1360.0, PivotType.LOW),
                        new Pivot(30, 1450.0, PivotType.HIGH),
                        new Pivot(35, 1340.0, PivotType.LOW),
                        new Pivot(40, 1480.0, PivotType.HIGH),
                        new Pivot(45, 1320.0, PivotType.LOW)),
                /*closeAtEnd=*/ 1400.0,
                /*closePrev=*/ 1400.0);

        assertTrue(rule.evaluate(ctx, List.of()).isEmpty(),
                "diverging / broadening pivots are not a triangle");
    }

    @Test
    void parallel_channel_rejected() {
        // Upper + lower lines roughly parallel (both flat). Not a triangle.
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1500.0, PivotType.HIGH),
                        new Pivot(15, 1300.0, PivotType.LOW),
                        new Pivot(20, 1502.0, PivotType.HIGH),
                        new Pivot(25, 1302.0, PivotType.LOW),
                        new Pivot(30, 1498.0, PivotType.HIGH),
                        new Pivot(35, 1301.0, PivotType.LOW),
                        new Pivot(40, 1499.0, PivotType.HIGH),
                        new Pivot(45, 1299.0, PivotType.LOW)),
                /*closeAtEnd=*/ 1400.0,
                /*closePrev=*/ 1395.0);

        assertTrue(rule.evaluate(ctx, List.of()).isEmpty(),
                "parallel channel (both lines flat) is not a triangle");
    }

    @Test
    void target_projects_pattern_height_from_breakout() {
        // Ascending, confirmed up break. Target should equal upperAtNow + heightAtStart.
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1300.0, PivotType.LOW),
                        new Pivot(15, 1502.0, PivotType.HIGH),
                        new Pivot(20, 1340.0, PivotType.LOW),
                        new Pivot(25, 1498.0, PivotType.HIGH),
                        new Pivot(30, 1380.0, PivotType.LOW),
                        new Pivot(35, 1501.0, PivotType.HIGH),
                        new Pivot(40, 1420.0, PivotType.LOW),
                        new Pivot(45, 1500.0, PivotType.HIGH)),
                1520.0, 1499.0);

        Firing f = rule.evaluate(ctx, List.of()).get(0);
        double trigger = ((Number) f.getPayload().get("trigger_price")).doubleValue();
        double target = ((Number) f.getPayload().get("target_price")).doubleValue();
        // Ascending up-break: target = trigger + (upperAtStart - lowerAtStart). Height at start
        // ≈ 1502-1300 = 202. Target ≈ 1500+202 = 1702.
        assertTrue(target > trigger,
                "ascending up-break: target above trigger; got target=" + target);
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
        BarSeries s = new BaseBarSeriesBuilder().withName("tri").build();
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
