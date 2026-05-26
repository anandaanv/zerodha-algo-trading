package com.dtech.aitrader.v2.rules.ew;

import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.Pass;
import com.dtech.aitrader.v2.rules.SymbolContext;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.PivotType;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.StructureLabel;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pass-1 EwWkClusterScanRule (Rule 5) — locks the Wk price-cluster scan against the blessed
 * RELIANCE reference (`cde6bbc9` criterion b). Three clusters expected: ~1361, ~1290-1307,
 * ~1473-1489.
 *
 * <p>Per blessed: cluster touch_count is the count of pivots within ±2% of the cluster centroid.
 * The blessed 1361 cluster has 3 touches; the 1290-1307 cluster has at least 3 once the full
 * 57-pivot Wk series is considered. The 1473-1489 cluster spans the 2024 1460-1512 highs plus
 * the 2026 1473/1489 touches — 3+ touches.
 */
class EwWkClusterScanRuleTest {

    private static final EwWkClusterScanRule RULE = new EwWkClusterScanRule();

    @Test
    void finds_blessed_RELIANCE_clusters() {
        SymbolContext ctx = relianceWeeklyClusterFixture();
        List<Firing> emitted = RULE.evaluate(ctx, List.of());

        assertFalse(emitted.isEmpty(), "must emit at least one cluster FACT");
        for (Firing f : emitted) {
            assertEquals(EwWkClusterScanRule.RULE_ID, f.getRuleId());
            assertEquals(Family.EW, f.getFamily());
            assertEquals(Pass.P1_STRUCTURAL, f.getPass());
            assertEquals(FiresOn.FACT, f.getFiresOn());
        }

        // Per owner override (75b20b10): clusters are mixed-type by price level. Each cluster
        // payload carries: centre, touch_count, role (support|resistance|mixed), high_touches,
        // low_touches, touch_dates, touch_prices, touch_kinds.
        List<Map<String, Object>> clusters = emitted.stream().map(Firing::getPayload).toList();

        // 1361 resistance cluster — 1361.25 + 1361.20 + 1359.30 = 3 HIGH touches.
        assertTrue(clusters.stream().anyMatch(p ->
                        Math.abs(((Number) p.get("centre")).doubleValue() - 1361.0) < 5.0
                                && ((Number) p.get("touch_count")).intValue() >= 3
                                && "resistance".equals(p.get("role"))),
                "expected ~1361 resistance cluster with ≥3 touches; got: " + clusters);

        // 1473-1489 resistance cluster — 1473.4 + 1489.5 + 1460 = 3+ HIGH touches.
        assertTrue(clusters.stream().anyMatch(p ->
                        Math.abs(((Number) p.get("centre")).doubleValue() - 1485.0) < 30.0
                                && ((Number) p.get("touch_count")).intValue() >= 3
                                && "resistance".equals(p.get("role"))),
                "expected ~1473-1489 resistance cluster with ≥3 touches; got: " + clusters);

        // 1290-1307 support cluster — 1290 (LOW) + 1307.7 (HIGH) + 1305 (LOW) + 1280 (LOW)
        // → 3 LOWs + 1 HIGH → role=support.
        assertTrue(clusters.stream().anyMatch(p ->
                        Math.abs(((Number) p.get("centre")).doubleValue() - 1295.0) < 20.0
                                && ((Number) p.get("touch_count")).intValue() >= 3
                                && "support".equals(p.get("role"))),
                "expected ~1290-1307 support cluster with ≥3 touches; got: " + clusters);
    }

    @Test
    void singletons_do_not_emit_clusters() {
        // Single isolated high should NOT emit a cluster.
        List<MarketStructurePoint> pivots = new ArrayList<>();
        pivots.add(pivot(2024, 1, 1, PivotType.HIGH, 100.0));
        pivots.add(pivot(2024, 3, 1, PivotType.LOW,  50.0));
        pivots.add(pivot(2024, 5, 1, PivotType.HIGH, 200.0));
        pivots.add(pivot(2024, 7, 1, PivotType.LOW,  25.0));
        pivots.add(pivot(2024, 9, 1, PivotType.HIGH, 300.0));
        pivots.add(pivot(2024, 11, 1, PivotType.LOW, 10.0));
        SymbolContext ctx = SymbolContext.builder()
                .symbol("ISO").tf("Week").asOf(LocalDate.of(2024, 12, 1))
                .pivots(pivots).build();

        List<Firing> emitted = RULE.evaluate(ctx, List.of());
        assertTrue(emitted.isEmpty(), "isolated pivots — no clusters; got: " + emitted);
    }

    // ── fixture ────────────────────────────────────────────────────────────────

    /**
     * RELIANCE Wk subset enriched to give each blessed cluster ≥3 touches. The two extra LOWs
     * (1305, 1280) are placeholders representing what the full 57-pivot series carries in the
     * 1290-1307 band.
     */
    private static SymbolContext relianceWeeklyClusterFixture() {
        List<MarketStructurePoint> pivots = new ArrayList<>();

        // 1361 resistance cluster — 3 touches
        pivots.add(pivot(2022, 4, 27, PivotType.HIGH, 1361.25));
        pivots.add(pivot(2023, 7, 19, PivotType.HIGH, 1361.20));
        pivots.add(pivot(2024, 5, 29, PivotType.HIGH, 1359.30));

        // 1473-1489 resistance cluster — 3+ touches (the 1460+1473+1489 band, 1512 spills outside)
        pivots.add(pivot(2024, 6, 24, PivotType.HIGH, 1460.00));
        pivots.add(pivot(2024, 7, 22, PivotType.HIGH, 1512.00));
        pivots.add(pivot(2026, 1, 28, PivotType.HIGH, 1489.50));
        pivots.add(pivot(2026, 4, 29, PivotType.HIGH, 1473.40));

        // 1290-1307 support cluster — 4 touches (enriched fixture for ≥3 acceptance)
        pivots.add(pivot(2017, 6, 12, PivotType.LOW,  1280.00));
        pivots.add(pivot(2020, 3, 23, PivotType.LOW,  1305.00));
        pivots.add(pivot(2025, 3, 19, PivotType.LOW,  1307.70));
        pivots.add(pivot(2026, 4,  1, PivotType.LOW,  1290.00));

        // Isolated extremes for context
        pivots.add(pivot(2022, 8, 15, PivotType.LOW,  1200.00));
        pivots.add(pivot(2025, 12, 31, PivotType.HIGH, 1611.80));

        return SymbolContext.builder()
                .symbol("RELIANCE").tf("Week").asOf(LocalDate.of(2026, 5, 22))
                .pivots(pivots).build();
    }

    private static MarketStructurePoint pivot(int y, int m, int d, PivotType type, double price) {
        return MarketStructurePoint.builder()
                .pivotType(type)
                .structureLabel(StructureLabel.FIRST)
                .timestamp(LocalDate.of(y, m, d).atStartOfDay().toInstant(ZoneOffset.UTC))
                .price(price)
                .atrAtPivot(50.0)
                .build();
    }
}
