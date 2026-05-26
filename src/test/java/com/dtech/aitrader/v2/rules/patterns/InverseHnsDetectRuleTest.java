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
 * InverseHnsDetectRule — mirror of {@link HnsDetectRuleTest} on LOW pivots. Locks the CONTINUOUS
 * completion formula per owner correction ({@code 89a52589}).
 */
class InverseHnsDetectRuleTest {

    private final InverseHnsDetectRule rule = new InverseHnsDetectRule();

    @Test
    void confirmed_status_when_neckline_broken_completion_100() {
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1300.0, PivotType.LOW),
                        new Pivot(20, 1350.0, PivotType.HIGH),
                        new Pivot(30, 1250.0, PivotType.LOW),
                        new Pivot(40, 1355.0, PivotType.HIGH),
                        new Pivot(50, 1302.0, PivotType.LOW)),
                /*closeAtEnd=*/ 1370.0,
                /*closePrev=*/ 1340.0);

        Firing f = rule.evaluate(ctx, List.of()).get(0);
        Map<String, Object> p = f.getPayload();
        assertEquals("confirmed", p.get("status"));
        assertEquals(100.0, ((Number) p.get("completion_pct")).doubleValue(), 1e-9);
        assertEquals("LONG", p.get("bias"));
    }

    @Test
    void forming_with_full_geometry_no_break_in_70_to_90_range() {
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1300.0, PivotType.LOW),
                        new Pivot(20, 1350.0, PivotType.HIGH),
                        new Pivot(30, 1250.0, PivotType.LOW),
                        new Pivot(40, 1355.0, PivotType.HIGH),
                        new Pivot(50, 1302.0, PivotType.LOW)),
                /*closeAtEnd=*/ 1340.0,
                /*closePrev=*/ 1330.0);

        Firing f = rule.evaluate(ctx, List.of()).get(0);
        Map<String, Object> p = f.getPayload();
        assertEquals("forming", p.get("status"));
        double completion = ((Number) p.get("completion_pct")).doubleValue();
        assertTrue(completion >= 70.0 && completion < 95.0,
                "full geometry, unbroken neckline → 70-95 range; got " + completion);
    }

    @Test
    void forming_with_partial_2_window_emits_when_rollover_meaningful() {
        // head=1250 below L=1300 by 50 (≥30 ATR). closeAtEnd=1280: rollup = (1280-1250)/(1350-1250) = 30/100 = 0.30
        // backbone(20) + 25*0.30=7.5 → ≈27.5. JUST above emission threshold.
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1300.0, PivotType.LOW),
                        new Pivot(20, 1350.0, PivotType.HIGH),
                        new Pivot(30, 1250.0, PivotType.LOW)),
                /*closeAtEnd=*/ 1290.0,    // rollup = (1290-1250)/100 = 0.40 → +10 → 30
                /*closePrev=*/ 1260.0);

        Firing f = rule.evaluate(ctx, List.of()).get(0);
        Map<String, Object> p = f.getPayload();
        assertEquals("forming", p.get("status"));
        double completion = ((Number) p.get("completion_pct")).doubleValue();
        assertTrue(completion >= 25.0 && completion < 60.0,
                "partial 2-window range; got " + completion);
    }

    @Test
    void no_firing_when_head_not_significantly_below_shoulder() {
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1300.0, PivotType.LOW),
                        new Pivot(20, 1350.0, PivotType.HIGH),
                        new Pivot(30, 1290.0, PivotType.LOW)),
                1320.0, 1305.0);
        assertTrue(rule.evaluate(ctx, List.of()).isEmpty());
    }

    @Test
    void no_firing_when_shoulders_too_unequal_at_full_window() {
        // Owner 474986f0 span-scaled tolerance — 40-bar span permits ~4.2%. Use 10%+ asymmetry.
        // L=1300 LOW vs R=1450 LOW → asym = 150 / 1200 (head) = 12.5% — rejected.
        SymbolContext ctx = ctxWithPattern(
                List.of(new Pivot(10, 1300.0, PivotType.LOW),
                        new Pivot(20, 1350.0, PivotType.HIGH),
                        new Pivot(30, 1200.0, PivotType.LOW),
                        new Pivot(40, 1355.0, PivotType.HIGH),
                        new Pivot(50, 1450.0, PivotType.LOW)),
                1370.0, 1340.0);
        assertTrue(rule.evaluate(ctx, List.of()).isEmpty());
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
        BarSeries s = new BaseBarSeriesBuilder().withName("ihns").build();
        Instant t0 = Instant.parse("2024-01-01T05:30:00Z");
        for (int i = 0; i < barCount; i++) {
            double c;
            if (i == barCount - 1) c = closeAtEnd;
            else if (i == barCount - 2) c = closePrev;
            else c = 1310.0;
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
