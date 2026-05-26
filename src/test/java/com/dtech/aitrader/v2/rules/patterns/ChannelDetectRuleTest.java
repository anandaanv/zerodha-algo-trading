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
 * ChannelDetectRule — locks SPEC-008 channel geometry: BOTH lines trending SAME direction with
 * matching slopes (parallel). Distinguishes from wedge (converging), triangle (≥1 flat) and
 * broadening (diverging).
 */
class ChannelDetectRuleTest {

    private final ChannelDetectRule rule = new ChannelDetectRule();

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
    void up_channel_detected_when_both_rising_parallel() {
        // Highs rising at +2.0/bar: 1400→1410→1420→1430→1440
        // Lows rising at +2.0/bar: 1300→1310→1320→1330→1340 (~constant 100 height)
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1300.0, PivotType.LOW),
                        new Pivot(15, 1400.0, PivotType.HIGH),
                        new Pivot(20, 1320.0, PivotType.LOW),
                        new Pivot(25, 1420.0, PivotType.HIGH),
                        new Pivot(30, 1340.0, PivotType.LOW),
                        new Pivot(35, 1440.0, PivotType.HIGH)),
                /*closeAtEnd=*/ 1420.0,
                /*closePrev=*/ 1415.0);

        List<Firing> out = rule.evaluate(ctx, List.of());
        Map<String, Object> p = latest(out).getPayload();
        assertEquals("up", p.get("channel_direction"));
        assertEquals("LONG", p.get("bias"));
        assertEquals("inside", p.get("channel_state"));
    }

    @Test
    void down_channel_detected_when_both_falling_parallel() {
        // Highs falling -2.0/bar, lows falling -2.0/bar (~constant 100 height).
        // At endIdx=59: upper extrapolates to ~1402, lower to ~1312 — close stays inside at 1350.
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1500.0, PivotType.HIGH),
                        new Pivot(15, 1400.0, PivotType.LOW),
                        new Pivot(20, 1480.0, PivotType.HIGH),
                        new Pivot(25, 1380.0, PivotType.LOW),
                        new Pivot(30, 1460.0, PivotType.HIGH),
                        new Pivot(35, 1360.0, PivotType.LOW)),
                /*closeAtEnd=*/ 1350.0,
                /*closePrev=*/ 1360.0);

        Firing f = latest(rule.evaluate(ctx, List.of()));
        Map<String, Object> p = f.getPayload();
        assertEquals("down", p.get("channel_direction"));
        assertEquals("SHORT", p.get("bias"));
        assertEquals("inside", p.get("channel_state"));
    }

    @Test
    void up_channel_exit_below_flips_bias_short() {
        // Up-channel with close NOW breaking below lower line ≈ 1340 + 2*(45-35) = 1360.
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1300.0, PivotType.LOW),
                        new Pivot(15, 1400.0, PivotType.HIGH),
                        new Pivot(20, 1320.0, PivotType.LOW),
                        new Pivot(25, 1420.0, PivotType.HIGH),
                        new Pivot(30, 1340.0, PivotType.LOW),
                        new Pivot(35, 1440.0, PivotType.HIGH)),
                /*closeAtEnd=*/ 1320.0,    // well below lower-line extrapolated to bar 59
                /*closePrev=*/ 1360.0);

        Firing f = latest(rule.evaluate(ctx, List.of()));
        Map<String, Object> p = f.getPayload();
        assertEquals("up", p.get("channel_direction"));
        assertEquals("exited_below", p.get("channel_state"));
        assertEquals("SHORT", p.get("bias"));
        assertEquals("confirmed", p.get("status"));
    }

    @Test
    void down_channel_exit_above_flips_bias_long() {
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1500.0, PivotType.HIGH),
                        new Pivot(15, 1400.0, PivotType.LOW),
                        new Pivot(20, 1480.0, PivotType.HIGH),
                        new Pivot(25, 1380.0, PivotType.LOW),
                        new Pivot(30, 1460.0, PivotType.HIGH),
                        new Pivot(35, 1360.0, PivotType.LOW)),
                /*closeAtEnd=*/ 1500.0,    // breaks above upper line extrapolated
                /*closePrev=*/ 1450.0);

        Firing f = latest(rule.evaluate(ctx, List.of()));
        Map<String, Object> p = f.getPayload();
        assertEquals("down", p.get("channel_direction"));
        assertEquals("exited_above", p.get("channel_state"));
        assertEquals("LONG", p.get("bias"));
        assertEquals("confirmed", p.get("status"));
    }

    @Test
    void converging_wedge_not_a_channel() {
        // Rising wedge: highs +0.8/bar (slow), lows +2.5/bar (fast) — clearly converging.
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1300.0, PivotType.LOW),
                        new Pivot(15, 1400.0, PivotType.HIGH),
                        new Pivot(20, 1325.0, PivotType.LOW),
                        new Pivot(25, 1408.0, PivotType.HIGH),
                        new Pivot(30, 1350.0, PivotType.LOW),
                        new Pivot(35, 1416.0, PivotType.HIGH),
                        new Pivot(40, 1375.0, PivotType.LOW),
                        new Pivot(45, 1424.0, PivotType.HIGH)),
                1418.0, 1415.0);
        assertTrue(rule.evaluate(ctx, List.of()).isEmpty(),
                "converging wedge must not classify as channel");
    }

    @Test
    void triangle_with_flat_line_not_a_channel() {
        // Ascending triangle: flat highs ≈ 1450, rising lows — one line flat = not a channel.
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1300.0, PivotType.LOW),
                        new Pivot(15, 1450.0, PivotType.HIGH),
                        new Pivot(20, 1330.0, PivotType.LOW),
                        new Pivot(25, 1450.0, PivotType.HIGH),
                        new Pivot(30, 1360.0, PivotType.LOW),
                        new Pivot(35, 1450.0, PivotType.HIGH),
                        new Pivot(40, 1390.0, PivotType.LOW),
                        new Pivot(45, 1450.0, PivotType.HIGH)),
                1410.0, 1408.0);
        assertTrue(rule.evaluate(ctx, List.of()).isEmpty(),
                "triangle (one flat line) must not classify as channel");
    }

    @Test
    void broadening_megaphone_not_a_channel() {
        // Upper rising, lower falling — diverging.
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
        assertTrue(rule.evaluate(ctx, List.of()).isEmpty(),
                "broadening megaphone must not classify as channel");
    }

    @Test
    void horizontal_flat_lines_not_a_channel() {
        // Both lines flat — that's rectangle territory, not channel.
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1300.0, PivotType.LOW),
                        new Pivot(15, 1450.0, PivotType.HIGH),
                        new Pivot(20, 1302.0, PivotType.LOW),
                        new Pivot(25, 1448.0, PivotType.HIGH),
                        new Pivot(30, 1301.0, PivotType.LOW),
                        new Pivot(35, 1452.0, PivotType.HIGH),
                        new Pivot(40, 1300.0, PivotType.LOW),
                        new Pivot(45, 1449.0, PivotType.HIGH)),
                1380.0, 1380.0);
        assertTrue(rule.evaluate(ctx, List.of()).isEmpty(),
                "horizontal flat-line range must not classify as channel");
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
        BarSeries s = new BaseBarSeriesBuilder().withName("channel").build();
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
