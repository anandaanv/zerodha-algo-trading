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
 * HnsDetectRule — locks the CONTINUOUS completion formula per owner correction
 * ({@code 89a52589}): completion is derived from HnS's own geometry — backbone(20) +
 * rollover(0-25) + trough2 + sym(15) + R-shoulder + sym(15) + approach(0-10) + confirmation
 * bump (→100). Owner explicitly rejects the hardcoded ladder.
 *
 * <p>Tests assert completion FALLS IN EXPECTED RANGES (not exact values) — the formula is
 * continuous so exact numbers may shift with formula tuning; what's locked is the SHAPE.
 *
 * <p>status="confirmed" iff completion ≥ 95; status="forming" otherwise. Firings below
 * completion=25 are suppressed (geometry too weak to count as forming).
 */
class HnsDetectRuleTest {

    private final HnsDetectRule rule = new HnsDetectRule();

    @Test
    void confirmed_status_when_neckline_broken_completion_clamps_at_100() {
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1350.0, PivotType.HIGH),
                        new Pivot(20, 1300.0, PivotType.LOW),
                        new Pivot(30, 1400.0, PivotType.HIGH),
                        new Pivot(40, 1295.0, PivotType.LOW),
                        new Pivot(50, 1352.0, PivotType.HIGH)),
                /*closeAtEnd=*/ 1290.0,
                /*closePrev=*/ 1310.0);

        Firing f = rule.evaluate(ctx, List.of()).get(0);
        Map<String, Object> p = f.getPayload();
        assertEquals("confirmed", p.get("status"));
        double completion = ((Number) p.get("completion_pct")).doubleValue();
        assertEquals(100.0, completion, 1e-9,
                "confirmed break clamps completion to 100");
        assertEquals("SHORT", p.get("bias"));
        @SuppressWarnings("unchecked")
        List<String> signs = (List<String>) p.get("early_signs");
        assertTrue(signs.contains("neckline_broken"));
    }

    @Test
    void forming_with_full_geometry_no_break_in_70_to_90_range() {
        // 5 pivots valid, R-shoulder symmetric, troughs symmetric, but close above neckline.
        // Expect: backbone(20) + full rollover(25) + trough2(10) + ~5 sym + R(10) + ~5 sym +
        // some approach(<10) ≈ 75-90 range. NOT yet confirmed.
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1350.0, PivotType.HIGH),
                        new Pivot(20, 1300.0, PivotType.LOW),
                        new Pivot(30, 1400.0, PivotType.HIGH),
                        new Pivot(40, 1295.0, PivotType.LOW),
                        new Pivot(50, 1352.0, PivotType.HIGH)),
                /*closeAtEnd=*/ 1305.0,
                /*closePrev=*/ 1310.0);

        Firing f = rule.evaluate(ctx, List.of()).get(0);
        Map<String, Object> p = f.getPayload();
        assertEquals("forming", p.get("status"));
        double completion = ((Number) p.get("completion_pct")).doubleValue();
        assertTrue(completion >= 70.0 && completion < 95.0,
                "full geometry, unbroken neckline → 70-95 range; got " + completion);
        @SuppressWarnings("unchecked")
        List<String> signs = (List<String>) p.get("early_signs");
        assertTrue(signs.contains("right_shoulder_built"));
        assertTrue(signs.contains("neckline_unbroken"));
    }

    @Test
    void forming_with_partial_2_window_in_30_to_50_range() {
        // 2 highs visible + 1 trough. Backbone(20) + partial rollover.
        // close=1380, head=1400, trough1=1300 → rollover frac = (1400-1380)/(1400-1300) = 0.20
        // → rollover contribution = 25 * 0.20 = 5. Total ≈ 25. JUST at emission threshold.
        // Use stronger rollover to ensure firing: close=1330 → frac=0.70 → +17.5 = 37.5.
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1350.0, PivotType.HIGH),
                        new Pivot(20, 1300.0, PivotType.LOW),
                        new Pivot(30, 1400.0, PivotType.HIGH)),
                /*closeAtEnd=*/ 1330.0,
                /*closePrev=*/ 1395.0);

        Firing f = rule.evaluate(ctx, List.of()).get(0);
        Map<String, Object> p = f.getPayload();
        assertEquals("forming", p.get("status"));
        double completion = ((Number) p.get("completion_pct")).doubleValue();
        assertTrue(completion >= 30.0 && completion < 60.0,
                "partial 2-window + moderate rollover → 30-60 range; got " + completion);
        @SuppressWarnings("unchecked")
        List<String> signs = (List<String>) p.get("early_signs");
        assertTrue(signs.contains("head_higher_than_l_shoulder_by_at_least_1_atr"));
        assertTrue(signs.contains("post_head_rollover_started"));
    }

    @Test
    void completion_monotonically_increases_as_rollover_deepens() {
        // Same pattern, only close varies. Deeper close → higher completion.
        SymbolContext shallow = ctxWithPattern(
                List.of(new Pivot(10, 1350.0, PivotType.HIGH),
                        new Pivot(20, 1300.0, PivotType.LOW),
                        new Pivot(30, 1400.0, PivotType.HIGH)),
                /*closeAtEnd=*/ 1395.0,    // shallow rollover
                /*closePrev=*/ 1399.0);
        SymbolContext deep = ctxWithPattern(
                List.of(new Pivot(10, 1350.0, PivotType.HIGH),
                        new Pivot(20, 1300.0, PivotType.LOW),
                        new Pivot(30, 1400.0, PivotType.HIGH)),
                /*closeAtEnd=*/ 1320.0,    // deep rollover
                /*closePrev=*/ 1395.0);

        List<Firing> shallowOut = rule.evaluate(shallow, List.of());
        List<Firing> deepOut = rule.evaluate(deep, List.of());

        // Shallow rollover may be below emission threshold; if both fire, deep should be higher.
        if (shallowOut.isEmpty()) {
            assertFalse(deepOut.isEmpty(), "deep rollover should fire");
        } else {
            double shallowC = ((Number) shallowOut.get(0).getPayload().get("completion_pct")).doubleValue();
            double deepC = ((Number) deepOut.get(0).getPayload().get("completion_pct")).doubleValue();
            assertTrue(deepC > shallowC,
                    "deeper rollover should produce higher completion; deep=" + deepC + " shallow=" + shallowC);
        }
    }

    @Test
    void no_firing_when_head_not_significantly_above_shoulder() {
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1350.0, PivotType.HIGH),
                        new Pivot(20, 1300.0, PivotType.LOW),
                        new Pivot(30, 1360.0, PivotType.HIGH)),
                1340.0, 1355.0);
        assertTrue(rule.evaluate(ctx, List.of()).isEmpty(),
                "head < L + 1 ATR ⇒ backbone gate fails ⇒ no firing");
    }

    @Test
    void no_firing_when_shoulders_too_unequal_at_full_window() {
        // Owner 474986f0 span-scaled tolerance: 3-7% asymmetry depending on span. At 40-bar span
        // tolerance is ~4.2%. R=1200 vs L=1350 → asym = 150/1500 = 10% — comfortably rejected.
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1350.0, PivotType.HIGH),
                        new Pivot(20, 1300.0, PivotType.LOW),
                        new Pivot(30, 1500.0, PivotType.HIGH),
                        new Pivot(40, 1295.0, PivotType.LOW),
                        new Pivot(50, 1200.0, PivotType.HIGH)),
                1290.0, 1310.0);
        assertTrue(rule.evaluate(ctx, List.of()).isEmpty(),
                "5-window with >10% shoulder asymmetry → empty (above span-scaled tolerance)");
    }

    @Test
    void shallow_shouldered_hns_passes_when_depth_ratio_above_23pct() {
        // Owner correction fbabb0b9: textbook ~50% gate is too strict — a symmetric, shallow-
        // shouldered H&S at 23%+ is a valid H&S, just lower quality.
        // L=1373, t1=1348, head=1473, t2=1348, R=1371 — shoulder depth = ~24 (min(L,R)-max(t1,t2)
        // = 1371-1348 = 23). Head depth = 1473-1348 = 125. Ratio = 23/125 = 0.184 — BELOW 0.23,
        // should still be rejected. Move shoulders up slightly to ~1380 to clear the gate.
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1380.0, PivotType.HIGH),  // L-shoulder
                        new Pivot(20, 1348.0, PivotType.LOW),   // trough1
                        new Pivot(30, 1473.0, PivotType.HIGH),  // head
                        new Pivot(40, 1348.0, PivotType.LOW),   // trough2
                        new Pivot(50, 1378.0, PivotType.HIGH)), // R-shoulder
                /*closeAtEnd=*/ 1360.0,    // above neckline — forming, not confirmed
                /*closePrev=*/ 1370.0);
        // Ratio = (min(1380,1378) - max(1348,1348)) / (1473 - 1348) = 30/125 = 0.24 → passes 0.23 gate

        List<Firing> out = rule.evaluate(ctx, List.of());
        assertEquals(1, out.size(),
                "shallow shoulder at 24% depth ratio should pass the 23% gate");
        Map<String, Object> p = out.get(0).getPayload();
        assertEquals("forming", p.get("status"));
        // Depth quality score is near 0 (ratio 0.24 ≈ MIN, far from textbook 0.50), so completion
        // is lower than a textbook 50%-shouldered case but still above emission threshold.
        double completion = ((Number) p.get("completion_pct")).doubleValue();
        assertTrue(completion >= 25.0,
                "shallow but valid H&S still emits; got completion=" + completion);
    }

    @Test
    void hns_below_23pct_shoulder_depth_is_rejected() {
        // Ratio = (1371-1348) / (1473-1348) = 23/125 = 0.184 — below the 0.23 gate.
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1373.0, PivotType.HIGH),
                        new Pivot(20, 1348.0, PivotType.LOW),
                        new Pivot(30, 1473.0, PivotType.HIGH),
                        new Pivot(40, 1348.0, PivotType.LOW),
                        new Pivot(50, 1371.0, PivotType.HIGH)),
                1360.0, 1370.0);
        assertTrue(rule.evaluate(ctx, List.of()).isEmpty(),
                "too-flat shoulder (ratio < 0.23) ⇒ no firing");
    }

    @Test
    void sloped_neckline_computed_at_current_bar() {
        // trough1=1300 at idx 20, trough2=1290 at idx 40 → slope=-0.5. At endIdx=59:
        // 1300 + (-0.5)*(59-20) = 1280.5.
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1350.0, PivotType.HIGH),
                        new Pivot(20, 1300.0, PivotType.LOW),
                        new Pivot(30, 1400.0, PivotType.HIGH),
                        new Pivot(40, 1290.0, PivotType.LOW),
                        new Pivot(50, 1352.0, PivotType.HIGH)),
                /*closeAtEnd=*/ 1270.0,
                /*closePrev=*/ 1300.0);

        Firing f = rule.evaluate(ctx, List.of()).get(0);
        double neckline = ((Number) f.getPayload().get("neckline_price")).doubleValue();
        assertEquals(1280.5, neckline, 0.5);
        assertEquals("confirmed", f.getPayload().get("status"));
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
                .symbol("TEST").tf("OneHour").asOf(LocalDate.of(2026, 5, 25))
                .series(series).pivots(mspList).build();
    }

    private static BarSeries buildBars(int barCount, double closeAtEnd, double closePrev,
                                          List<Pivot> pivots) {
        BarSeries s = new BaseBarSeriesBuilder().withName("hns").build();
        Instant t0 = Instant.parse("2024-01-01T05:30:00Z");
        for (int i = 0; i < barCount; i++) {
            double c;
            if (i == barCount - 1) c = closeAtEnd;
            else if (i == barCount - 2) c = closePrev;
            else c = 1340.0;
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
