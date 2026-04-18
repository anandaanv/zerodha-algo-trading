package com.dtech.kitecon.elliott.choch;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.dtech.kitecon.elliott.ImpulseLabeler;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;

/**
 * Unit tests for ChochStateImpulseLabeler.
 *
 * Tests Elliott Wave impulse detection (wave 3 and wave 5 starts) using
 * ChoCH (Change of Character) market structure events.
 */
public class ChochStateImpulseLabelerTest {

    private final ChochStateImpulseLabeler labeler = new ChochStateImpulseLabeler();

    // ========== Helper Methods ==========

    private ZigZagPoint pivot(ZigZagPoint.Type type, int barIndex, double price) {
        return ZigZagPoint.builder()
            .type(type)
            .barIndex(barIndex)
            .value(price)
            .timestamp(Instant.ofEpochSecond(barIndex * 3600L))
            .build();
    }

    private MarketStructurePoint choch(MarketStructurePoint.StructureLabel kind, int barIndex, double price) {
        return MarketStructurePoint.builder()
            .pivotType(kind == MarketStructurePoint.StructureLabel.CHOCH_HIGH
                ? MarketStructurePoint.PivotType.HIGH
                : MarketStructurePoint.PivotType.LOW)
            .structureLabel(kind)
            .timestamp(Instant.ofEpochSecond(barIndex * 3600L))
            .price(price)
            .build();
    }

    private int indexOfLabel(List<ImpulseLabeler.LabeledPivot> results, String label) {
        for (int i = 0; i < results.size(); i++) {
            if (label.equals(results.get(i).label())) {
                return i;
            }
        }
        return -1;
    }

    private long countLabels(List<ImpulseLabeler.LabeledPivot> results, String label) {
        return results.stream().filter(r -> label.equals(r.label())).count();
    }

    // ========== Test Cases ==========

    /**
     * T1: Textbook bullish impulse with LOW confidence W2 (retrace 55%, below 61.8%).
     *
     * Setup:
     * - Reversal anchor at pivot 0 (LOW, 100): W1 origin
     * - pivots: (0, LOW, 100), (1, HIGH, 140), (2, LOW, 118), (3, HIGH, 220), (4, HIGH, 220)
     * - ChoCHs: CHOCH_HIGH@1 (anchors to pivot 0), CHOCH_LOW@2 (anchors to pivot 1), CHOCH_HIGH@4 (anchors to pivot 3)
     * - W1: 100→140, size=40. W2 retrace: (140-118)/40 = 55% (LOW conf). W3: 118→220, size=102, ratio=2.55x
     *
     * Expected: 1 wave3_start label (at pivot 1), direction=1, no wave5_start
     */
    @Test
    void testBullishImpulseLowConfidenceW2() {
        List<ZigZagPoint> pivots = new ArrayList<>();
        pivots.add(pivot(ZigZagPoint.Type.LOW, 0, 100.0));
        pivots.add(pivot(ZigZagPoint.Type.HIGH, 1, 140.0));
        pivots.add(pivot(ZigZagPoint.Type.LOW, 2, 118.0));
        pivots.add(pivot(ZigZagPoint.Type.HIGH, 3, 220.0));
        pivots.add(pivot(ZigZagPoint.Type.HIGH, 4, 220.0));

        List<MarketStructurePoint> structurePoints = new ArrayList<>();
        structurePoints.add(choch(MarketStructurePoint.StructureLabel.CHOCH_HIGH, 1, 140.0));
        structurePoints.add(choch(MarketStructurePoint.StructureLabel.CHOCH_LOW, 2, 118.0));
        structurePoints.add(choch(MarketStructurePoint.StructureLabel.CHOCH_HIGH, 4, 220.0));

        List<ImpulseLabeler.LabeledPivot> results = labeler.label(
            pivots, Collections.emptyList(), Collections.emptyMap(), structurePoints);

        assertAll("T1: Bullish impulse LOW conf W2",
            () -> assertEquals(5, results.size()),
            () -> assertEquals(1L, countLabels(results, "wave3_start"), "Should have exactly 1 wave3_start"),
            () -> assertEquals(0L, countLabels(results, "wave5_start"), "Should have no wave5_start"),
            () -> assertEquals(1, results.get(indexOfLabel(results, "wave3_start")).direction(), "Direction should be bullish (1)")
        );
    }

    /**
     * T2: HIGH confidence W2 (retrace 75%, >= 61.8%).
     *
     * Setup:
     * - Reversal anchor at pivot 0 (LOW, 100): W1 origin
     * - pivots: (0, LOW, 100), (1, HIGH, 140), (2, LOW, 110), (3, HIGH, 220), (4, HIGH, 220)
     * - ChoCHs: CHOCH_HIGH@1, CHOCH_LOW@2, CHOCH_HIGH@4
     * - W1: 100→140, size=40. W2 retrace: (140-110)/40 = 75% (HIGH conf >= 61.8%). W3: 110→220, size=110
     *
     * Expected: 1 wave3_start with detail containing "HIGH" confidence marker
     */
    @Test
    void testBullishImpulseHighConfidenceW2() {
        List<ZigZagPoint> pivots = new ArrayList<>();
        pivots.add(pivot(ZigZagPoint.Type.LOW, 0, 100.0));
        pivots.add(pivot(ZigZagPoint.Type.HIGH, 1, 140.0));
        pivots.add(pivot(ZigZagPoint.Type.LOW, 2, 110.0));
        pivots.add(pivot(ZigZagPoint.Type.HIGH, 3, 220.0));
        pivots.add(pivot(ZigZagPoint.Type.HIGH, 4, 220.0));

        List<MarketStructurePoint> structurePoints = new ArrayList<>();
        structurePoints.add(choch(MarketStructurePoint.StructureLabel.CHOCH_HIGH, 1, 140.0));
        structurePoints.add(choch(MarketStructurePoint.StructureLabel.CHOCH_LOW, 2, 110.0));
        structurePoints.add(choch(MarketStructurePoint.StructureLabel.CHOCH_HIGH, 4, 220.0));

        List<ImpulseLabeler.LabeledPivot> results = labeler.label(
            pivots, Collections.emptyList(), Collections.emptyMap(), structurePoints);

        int wave3Idx = indexOfLabel(results, "wave3_start");
        assertAll("T2: Bullish impulse HIGH conf W2",
            () -> assertEquals(1L, countLabels(results, "wave3_start"), "Should have exactly 1 wave3_start"),
            () -> assertTrue(wave3Idx >= 0, "wave3_start label should exist"),
            () -> assertTrue(results.get(wave3Idx).detail().contains("HIGH"),
                "Detail should contain HIGH confidence marker")
        );
    }

    /**
     * T3: W2 retrace too shallow (<50%, fails minimum threshold).
     *
     * Setup:
     * - pivots: (0, LOW, 100), (1, HIGH, 140), (2, LOW, 125), (3, HIGH, 220), (4, HIGH, 220)
     * - W1: 100→140 (size=40). W2 retrace: (140-125)/40 = 37.5% (fails 50% minimum)
     *
     * Expected: 0 wave3_start, 0 wave5_start (pending stays unconfirmed)
     */
    @Test
    void testW2RetraceTooShallow() {
        List<ZigZagPoint> pivots = new ArrayList<>();
        pivots.add(pivot(ZigZagPoint.Type.LOW, 0, 100.0));
        pivots.add(pivot(ZigZagPoint.Type.HIGH, 1, 140.0));
        pivots.add(pivot(ZigZagPoint.Type.LOW, 2, 125.0));
        pivots.add(pivot(ZigZagPoint.Type.HIGH, 3, 220.0));
        pivots.add(pivot(ZigZagPoint.Type.HIGH, 4, 220.0));

        List<MarketStructurePoint> structurePoints = new ArrayList<>();
        structurePoints.add(choch(MarketStructurePoint.StructureLabel.CHOCH_HIGH, 1, 140.0));
        structurePoints.add(choch(MarketStructurePoint.StructureLabel.CHOCH_LOW, 2, 125.0));
        structurePoints.add(choch(MarketStructurePoint.StructureLabel.CHOCH_HIGH, 4, 220.0));

        List<ImpulseLabeler.LabeledPivot> results = labeler.label(
            pivots, Collections.emptyList(), Collections.emptyMap(), structurePoints);

        assertAll("T3: W2 retrace too shallow",
            () -> assertEquals(0L, countLabels(results, "wave3_start"), "Should have no wave3_start"),
            () -> assertEquals(0L, countLabels(results, "wave5_start"), "Should have no wave5_start")
        );
    }

    /**
     * T4: W2 invalidation (retrace > 100%, W2 end below origin).
     *
     * Setup:
     * - pivots: (0, LOW, 100), (1, HIGH, 140), (2, LOW, 90), (3, HIGH, 220), (4, HIGH, 220)
     * - W1: 100→140 (size=40). W2 end at 90, below origin 100, retrace 125% (invalidates)
     *
     * Expected: 0 wave3_start (impulse invalidated, pending resets)
     */
    @Test
    void testW2InvalidationBelowOrigin() {
        List<ZigZagPoint> pivots = new ArrayList<>();
        pivots.add(pivot(ZigZagPoint.Type.LOW, 0, 100.0));
        pivots.add(pivot(ZigZagPoint.Type.HIGH, 1, 140.0));
        pivots.add(pivot(ZigZagPoint.Type.LOW, 2, 90.0));
        pivots.add(pivot(ZigZagPoint.Type.HIGH, 3, 220.0));
        pivots.add(pivot(ZigZagPoint.Type.HIGH, 4, 220.0));

        List<MarketStructurePoint> structurePoints = new ArrayList<>();
        structurePoints.add(choch(MarketStructurePoint.StructureLabel.CHOCH_HIGH, 1, 140.0));
        structurePoints.add(choch(MarketStructurePoint.StructureLabel.CHOCH_LOW, 2, 90.0));
        structurePoints.add(choch(MarketStructurePoint.StructureLabel.CHOCH_HIGH, 4, 220.0));

        List<ImpulseLabeler.LabeledPivot> results = labeler.label(
            pivots, Collections.emptyList(), Collections.emptyMap(), structurePoints);

        assertEquals(0L, countLabels(results, "wave3_start"),
            "Should have no wave3_start (W2 invalidated)");
    }

    /**
     * T5: W3 ratio too weak (<1.61x, fails minimum extension requirement).
     *
     * Setup:
     * - pivots: (0, LOW, 100), (1, HIGH, 140), (2, LOW, 118), (3, HIGH, 170), (4, HIGH, 170)
     * - W1: 100→140 (size=40). W2: 140→118 (52% retrace, valid). W3: 118→170 (size=52, ratio=1.3x, fails 1.61x)
     *
     * Expected: 0 wave3_start (W3 too weak, pending stays PENDING_W3)
     */
    @Test
    void testW3RatioTooWeak() {
        List<ZigZagPoint> pivots = new ArrayList<>();
        pivots.add(pivot(ZigZagPoint.Type.LOW, 0, 100.0));
        pivots.add(pivot(ZigZagPoint.Type.HIGH, 1, 140.0));
        pivots.add(pivot(ZigZagPoint.Type.LOW, 2, 118.0));
        pivots.add(pivot(ZigZagPoint.Type.HIGH, 3, 170.0));
        pivots.add(pivot(ZigZagPoint.Type.HIGH, 4, 170.0));

        List<MarketStructurePoint> structurePoints = new ArrayList<>();
        structurePoints.add(choch(MarketStructurePoint.StructureLabel.CHOCH_HIGH, 1, 140.0));
        structurePoints.add(choch(MarketStructurePoint.StructureLabel.CHOCH_LOW, 2, 118.0));
        structurePoints.add(choch(MarketStructurePoint.StructureLabel.CHOCH_HIGH, 4, 170.0));

        List<ImpulseLabeler.LabeledPivot> results = labeler.label(
            pivots, Collections.emptyList(), Collections.emptyMap(), structurePoints);

        assertEquals(0L, countLabels(results, "wave3_start"),
            "Should have no wave3_start (W3 ratio too weak)");
    }

    /**
     * T6: Full W1→W5 sequence (complete 5-wave bullish impulse).
     *
     * Setup:
     * - pivots: (0, LOW, 100), (1, HIGH, 140), (2, LOW, 118), (3, HIGH, 220), (4, HIGH, 220),
     *          (5, LOW, 183), (6, HIGH, 260), (7, HIGH, 260)
     * - ChoCHs: CHOCH_HIGH@1, CHOCH_LOW@2, CHOCH_HIGH@4, CHOCH_LOW@5, CHOCH_HIGH@7
     * - W1: 100→140 (size=40). W2: 140→118 (55% retrace). W3: 118→220 (size=102, ratio=2.55x).
     * - W4: 220→183 (36.3% of W3=102, retrace valid). W5: 183→260 (size=77, ratio=0.755x W3, >= 0.618)
     *
     * Expected: 1 wave3_start at pivot idx 1, 1 wave5_start at pivot idx 5, both direction=1
     */
    @Test
    void testFullW1ToW5Sequence() {
        List<ZigZagPoint> pivots = new ArrayList<>();
        pivots.add(pivot(ZigZagPoint.Type.LOW, 0, 100.0));
        pivots.add(pivot(ZigZagPoint.Type.HIGH, 1, 140.0));
        pivots.add(pivot(ZigZagPoint.Type.LOW, 2, 118.0));
        pivots.add(pivot(ZigZagPoint.Type.HIGH, 3, 220.0));
        pivots.add(pivot(ZigZagPoint.Type.HIGH, 4, 220.0));
        pivots.add(pivot(ZigZagPoint.Type.LOW, 5, 183.0));
        pivots.add(pivot(ZigZagPoint.Type.HIGH, 6, 260.0));
        pivots.add(pivot(ZigZagPoint.Type.HIGH, 7, 260.0));

        List<MarketStructurePoint> structurePoints = new ArrayList<>();
        structurePoints.add(choch(MarketStructurePoint.StructureLabel.CHOCH_HIGH, 1, 140.0));
        structurePoints.add(choch(MarketStructurePoint.StructureLabel.CHOCH_LOW, 2, 118.0));
        structurePoints.add(choch(MarketStructurePoint.StructureLabel.CHOCH_HIGH, 4, 220.0));
        structurePoints.add(choch(MarketStructurePoint.StructureLabel.CHOCH_LOW, 5, 183.0));
        structurePoints.add(choch(MarketStructurePoint.StructureLabel.CHOCH_HIGH, 7, 260.0));

        List<ImpulseLabeler.LabeledPivot> results = labeler.label(
            pivots, Collections.emptyList(), Collections.emptyMap(), structurePoints);

        int wave3Idx = indexOfLabel(results, "wave3_start");
        int wave5Idx = indexOfLabel(results, "wave5_start");

        assertAll("T6: Full W1→W5 sequence",
            () -> assertEquals(1L, countLabels(results, "wave3_start"), "Should have exactly 1 wave3_start"),
            () -> assertEquals(1L, countLabels(results, "wave5_start"), "Should have exactly 1 wave5_start"),
            () -> assertEquals(1, wave3Idx, "wave3_start should be at pivot index 1 (W2 end anchor)"),
            () -> assertEquals(4, wave5Idx, "wave5_start should be at pivot index 4 (W4 end anchor)"),
            () -> assertEquals(1, results.get(wave3Idx).direction(), "wave3 direction should be bullish (1)"),
            () -> assertEquals(1, results.get(wave5Idx).direction(), "wave5 direction should be bullish (1)")
        );
    }

    /**
     * T7: W4 overlaps W1 end (W4 end price < W1 end price, violates non-overlap rule).
     *
     * Setup:
     * - pivots: (0, LOW, 100), (1, HIGH, 140), (2, LOW, 118), (3, HIGH, 220), (4, HIGH, 220), (5, LOW, 135)
     * - ChoCHs: CHOCH_HIGH@1, CHOCH_LOW@2, CHOCH_HIGH@4, CHOCH_LOW@5
     * - W4 end 135 < W1 end 140 (overlap violation)
     *
     * Expected: 1 wave3_start (already emitted), 0 wave5_start (overlap prevents W5)
     */
    @Test
    void testW4OverlapsW1End() {
        List<ZigZagPoint> pivots = new ArrayList<>();
        pivots.add(pivot(ZigZagPoint.Type.LOW, 0, 100.0));
        pivots.add(pivot(ZigZagPoint.Type.HIGH, 1, 140.0));
        pivots.add(pivot(ZigZagPoint.Type.LOW, 2, 118.0));
        pivots.add(pivot(ZigZagPoint.Type.HIGH, 3, 220.0));
        pivots.add(pivot(ZigZagPoint.Type.HIGH, 4, 220.0));
        pivots.add(pivot(ZigZagPoint.Type.LOW, 5, 135.0));

        List<MarketStructurePoint> structurePoints = new ArrayList<>();
        structurePoints.add(choch(MarketStructurePoint.StructureLabel.CHOCH_HIGH, 1, 140.0));
        structurePoints.add(choch(MarketStructurePoint.StructureLabel.CHOCH_LOW, 2, 118.0));
        structurePoints.add(choch(MarketStructurePoint.StructureLabel.CHOCH_HIGH, 4, 220.0));
        structurePoints.add(choch(MarketStructurePoint.StructureLabel.CHOCH_LOW, 5, 135.0));

        List<ImpulseLabeler.LabeledPivot> results = labeler.label(
            pivots, Collections.emptyList(), Collections.emptyMap(), structurePoints);

        assertAll("T7: W4 overlaps W1 end",
            () -> assertEquals(1L, countLabels(results, "wave3_start"), "Should have 1 wave3_start (already emitted)"),
            () -> assertEquals(0L, countLabels(results, "wave5_start"), "Should have no wave5_start (overlap prevents it)")
        );
    }

    /**
     * T8: Bearish mirror of T1 (inverted price structure and ChoCH directions).
     *
     * Setup:
     * - pivots: (0, HIGH, 200), (1, LOW, 160), (2, HIGH, 182), (3, LOW, 100), (4, LOW, 100)
     * - ChoCHs: CHOCH_LOW@1, CHOCH_HIGH@2, CHOCH_LOW@4
     * - W1: 200→160 (size=40 down). W2: 160→182 (55% retrace up, valid). W3: 182→100 (size=82, ratio=2.05x)
     *
     * Expected: 1 wave3_start, direction=-1 (bearish)
     */
    @Test
    void testBearishMirror() {
        List<ZigZagPoint> pivots = new ArrayList<>();
        pivots.add(pivot(ZigZagPoint.Type.HIGH, 0, 200.0));
        pivots.add(pivot(ZigZagPoint.Type.LOW, 1, 160.0));
        pivots.add(pivot(ZigZagPoint.Type.HIGH, 2, 182.0));
        pivots.add(pivot(ZigZagPoint.Type.LOW, 3, 100.0));
        pivots.add(pivot(ZigZagPoint.Type.LOW, 4, 100.0));

        List<MarketStructurePoint> structurePoints = new ArrayList<>();
        structurePoints.add(choch(MarketStructurePoint.StructureLabel.CHOCH_LOW, 1, 160.0));
        structurePoints.add(choch(MarketStructurePoint.StructureLabel.CHOCH_HIGH, 2, 182.0));
        structurePoints.add(choch(MarketStructurePoint.StructureLabel.CHOCH_LOW, 4, 100.0));

        List<ImpulseLabeler.LabeledPivot> results = labeler.label(
            pivots, Collections.emptyList(), Collections.emptyMap(), structurePoints);

        int wave3Idx = indexOfLabel(results, "wave3_start");
        assertAll("T8: Bearish mirror",
            () -> assertEquals(1L, countLabels(results, "wave3_start"), "Should have 1 wave3_start"),
            () -> assertTrue(wave3Idx >= 0, "wave3_start should exist"),
            () -> assertEquals(-1, results.get(wave3Idx).direction(), "Direction should be bearish (-1)")
        );
    }

    /**
     * T9: Same-direction ChoCH in a row (orphan pattern).
     *
     * Setup:
     * - pivots: (0, LOW, 100), (1, HIGH, 105), (2, HIGH, 110)
     * - ChoCHs: CHOCH_HIGH@1, CHOCH_HIGH@2 (both same direction, second is orphan)
     *
     * Expected: 0 wave3_start, 0 wave5_start (second ChoCH is orphan, no impulse forms)
     */
    @Test
    void testSameDirectionChochInARow() {
        List<ZigZagPoint> pivots = new ArrayList<>();
        pivots.add(pivot(ZigZagPoint.Type.LOW, 0, 100.0));
        pivots.add(pivot(ZigZagPoint.Type.HIGH, 1, 105.0));
        pivots.add(pivot(ZigZagPoint.Type.HIGH, 2, 110.0));

        List<MarketStructurePoint> structurePoints = new ArrayList<>();
        structurePoints.add(choch(MarketStructurePoint.StructureLabel.CHOCH_HIGH, 1, 105.0));
        structurePoints.add(choch(MarketStructurePoint.StructureLabel.CHOCH_HIGH, 2, 110.0));

        List<ImpulseLabeler.LabeledPivot> results = labeler.label(
            pivots, Collections.emptyList(), Collections.emptyMap(), structurePoints);

        assertAll("T9: Same-direction ChoCH in a row",
            () -> assertEquals(0L, countLabels(results, "wave3_start"), "Should have no wave3_start"),
            () -> assertEquals(0L, countLabels(results, "wave5_start"), "Should have no wave5_start")
        );
    }

    /**
     * T10: Empty ChoCH stream (no market structure events).
     *
     * Setup:
     * - pivots: (0, LOW, 100), (1, HIGH, 110)
     * - structurePoints: empty list
     *
     * Expected: results.size()==2, all labels=="no_impulse" (all pivots get no_impulse label)
     */
    @Test
    void testEmptyChochStream() {
        List<ZigZagPoint> pivots = new ArrayList<>();
        pivots.add(pivot(ZigZagPoint.Type.LOW, 0, 100.0));
        pivots.add(pivot(ZigZagPoint.Type.HIGH, 1, 110.0));

        List<MarketStructurePoint> structurePoints = new ArrayList<>();

        List<ImpulseLabeler.LabeledPivot> results = labeler.label(
            pivots, Collections.emptyList(), Collections.emptyMap(), structurePoints);

        assertAll("T10: Empty ChoCH stream",
            () -> assertEquals(2, results.size(), "Should have labels for all 2 pivots"),
            () -> assertEquals(2L, countLabels(results, "no_impulse"), "All labels should be no_impulse")
        );
    }
}
