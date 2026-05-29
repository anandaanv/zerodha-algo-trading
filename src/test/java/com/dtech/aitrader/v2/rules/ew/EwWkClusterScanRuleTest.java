package com.dtech.aitrader.v2.rules.ew;

import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.Pass;
import com.dtech.aitrader.v2.rules.SymbolContext;
import com.dtech.aitrader.v2.rules.ew.dwell.Direction;
import com.dtech.aitrader.v2.rules.ew.dwell.DwellPivot;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.PivotType;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.StructureLabel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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

    private static final EwClusterScanRule RULE =
            new EwClusterScanRule("Week", EwClusterRuleIds.WK, 2.0, 3);

    @Test
    void finds_blessed_RELIANCE_clusters() {
        SymbolContext ctx = relianceWeeklyClusterFixture();
        List<Firing> emitted = RULE.evaluate(ctx, List.of());

        assertFalse(emitted.isEmpty(), "must emit at least one cluster FACT");
        for (Firing f : emitted) {
            assertEquals(EwClusterRuleIds.WK, f.getRuleId());
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
    void dwell_pivots_contribute_touches_to_a_cluster_at_their_centre() {
        // Spec contract (SPEC-010 Phase 1 / 60d21c43 / 59fa728f): dwell pivots feed the cluster
        // scan as additional touches. Two reversal LOWs near 1290 (below minTouches=3) plus a
        // DWELL with center 1295 (HH = continuation-support) should promote the 1290-zone to a
        // 3-touch cluster.
        List<MarketStructurePoint> pivots = new ArrayList<>();
        pivots.add(pivot(2024,  3, 11, PivotType.LOW, 1290.0));
        pivots.add(pivot(2025,  9, 22, PivotType.LOW, 1305.0));
        // High side: a singleton far above to confirm it does NOT cluster
        pivots.add(pivot(2025, 12, 31, PivotType.HIGH, 1611.8));

        DwellPivot dwell = DwellPivot.builder()
                .tf("Week")
                .startTimestamp(Instant.parse("2026-03-02T05:30:00Z"))
                .endTimestamp(Instant.parse("2026-03-23T05:30:00Z"))
                .startIdx(50).endIdx(53)
                .centerPrice(1295.0).bandHi(1300.0).bandLo(1290.0)
                .atrUsed(30.0).barCount(4)
                .direction(Direction.HH) // support shelf
                .build();

        SymbolContext ctx = SymbolContext.builder()
                .symbol("DWELL-TEST").tf("Week").asOf(LocalDate.of(2026, 5, 22))
                .pivots(pivots)
                .dwellPivots(List.of(dwell))
                .build();

        List<Firing> emitted = RULE.evaluate(ctx, List.of());
        assertEquals(1, emitted.size(),
                "exactly one cluster expected (1290 zone promoted by dwell); got: " + emitted);
        Map<String, Object> payload = emitted.get(0).getPayload();
        assertEquals(3, ((Number) payload.get("touch_count")).intValue(),
                "cluster touch_count must include the dwell-derived touch");
        assertEquals(1, ((Number) payload.get("dwell_touches")).intValue(),
                "exactly one dwell-derived touch in this cluster");
        // HH-dwell maps to a synthetic LOW touch → support role wins (2 LOW reversals + 1 LOW dwell)
        assertEquals("support", payload.get("role"));
    }

    @Test
    void dwell_pivots_filtered_by_TF_do_not_contribute_when_tf_mismatches() {
        // Spec contract (59fa728f): cluster scan reads dwell pivots for ITS tf (Week here).
        // A Day-tagged dwell at the same price must NOT contribute to the weekly cluster.
        List<MarketStructurePoint> pivots = new ArrayList<>();
        pivots.add(pivot(2024,  3, 11, PivotType.LOW, 1290.0));
        pivots.add(pivot(2025,  9, 22, PivotType.LOW, 1305.0));

        DwellPivot dayDwell = DwellPivot.builder()
                .tf("Day")  // not Week
                .centerPrice(1295.0).bandHi(1300.0).bandLo(1290.0)
                .startTimestamp(Instant.parse("2026-03-02T05:30:00Z"))
                .atrUsed(30.0).barCount(4)
                .direction(Direction.HH)
                .build();

        SymbolContext ctx = SymbolContext.builder()
                .symbol("DWELL-TEST").tf("Week").asOf(LocalDate.of(2026, 5, 22))
                .pivots(pivots)
                .dwellPivots(List.of(dayDwell))
                .build();

        List<Firing> emitted = RULE.evaluate(ctx, List.of());
        assertTrue(emitted.isEmpty(),
                "Day-tagged dwell must not contribute to Week cluster scan; got: " + emitted);
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
