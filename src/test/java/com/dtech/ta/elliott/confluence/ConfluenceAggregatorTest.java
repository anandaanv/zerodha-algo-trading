package com.dtech.ta.elliott.confluence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfluenceAggregatorTest {

    private ConfluenceAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new ConfluenceAggregator();
    }

    private PriceLevel level(double price, double tol, String src) {
        return PriceLevel.builder().price(price).label("L").sourceType(src).tolerance(tol).build();
    }

    @Test
    void non_overlapping_levels_produce_separate_zones() {
        var levels = List.of(
                level(100, 1.0, "FIB_RETRACEMENT"),
                level(150, 1.0, "HORIZONTAL_SR"),
                level(200, 1.0, "FIB_EXTENSION")
        );
        var zones = aggregator.aggregate(levels);
        assertEquals(3, zones.size());
        zones.forEach(z -> assertEquals(1, z.getFactorCount()));
    }

    @Test
    void overlapping_fib_and_sr_merged_into_one_zone() {
        var levels = List.of(
                level(100, 2.0, "FIB_RETRACEMENT"),
                level(101, 2.0, "HORIZONTAL_SR")
        );
        var zones = aggregator.aggregate(levels);
        assertEquals(1, zones.size());
        assertEquals(2, zones.get(0).getFactorCount());
        assertEquals(2, zones.get(0).getFactorDiversity());
        assertEquals("MIXED", zones.get(0).getZoneType());
    }

    @Test
    void sorted_by_score_descending() {
        // Create zones indirectly by making clusters of different sizes
        var levels = List.of(
                level(100, 2.0, "FIB_RETRACEMENT"),
                level(101, 2.0, "HORIZONTAL_SR"),
                level(102, 2.0, "FIB_EXTENSION"), // 3-level cluster => score=9
                level(300, 1.0, "HORIZONTAL_SR")   // 1-level cluster => score=1
        );
        var zones = aggregator.aggregate(levels);
        assertTrue(zones.get(0).getScore() >= zones.get(1).getScore());
    }

    @Test
    void empty_input_returns_empty() {
        assertTrue(aggregator.aggregate(List.of()).isEmpty());
    }

    @Test
    void null_input_returns_empty() {
        assertTrue(aggregator.aggregate(null).isEmpty());
    }

    @Test
    void sr_only_zone_type() {
        var levels = List.of(level(100, 1.0, "HORIZONTAL_SR"));
        var zones = aggregator.aggregate(levels);
        assertEquals("SR_ONLY", zones.get(0).getZoneType());
    }

    @Test
    void fib_cluster_zone_type() {
        var levels = List.of(
                level(100, 2.0, "FIB_RETRACEMENT"),
                level(101, 2.0, "FIB_EXTENSION")
        );
        var zones = aggregator.aggregate(levels);
        assertEquals("FIB_CLUSTER", zones.get(0).getZoneType());
    }
}
