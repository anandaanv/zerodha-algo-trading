package com.dtech.ta.elliott.confluence;

import com.dtech.chartpattern.zigzag.ZigZagPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HorizontalSRBuilderTest {

    private HorizontalSRBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new HorizontalSRBuilder();
    }

    private ZigZagPoint pivot(double price) {
        return ZigZagPoint.builder().type(ZigZagPoint.Type.HIGH).barIndex(0).value(price).build();
    }

    @Test
    void cluster_nearby_pivots() {
        // 4 pivots very close together — all within 2% of each other
        var pivots = List.of(pivot(100.0), pivot(100.5), pivot(99.8), pivot(101.0));
        var levels = builder.buildSRLevels(pivots, 0.02);
        assertEquals(1, levels.size());
        assertTrue(levels.get(0).getLabel().contains("4"));
    }

    @Test
    void separate_clusters() {
        // Two distinct groups
        var pivots = List.of(pivot(100.0), pivot(100.5), pivot(200.0), pivot(200.3));
        var levels = builder.buildSRLevels(pivots, 0.01);
        assertEquals(2, levels.size());
    }

    @Test
    void single_pivot() {
        var levels = builder.buildSRLevels(List.of(pivot(150.0)), 0.01);
        assertEquals(1, levels.size());
        assertEquals(150.0, levels.get(0).getPrice(), 0.001);
    }

    @Test
    void empty_input_returns_empty() {
        var levels = builder.buildSRLevels(List.of(), 0.01);
        assertTrue(levels.isEmpty());
    }

    @Test
    void null_input_returns_empty() {
        var levels = builder.buildSRLevels(null, 0.01);
        assertTrue(levels.isEmpty());
    }

    @Test
    void source_type_is_HORIZONTAL_SR() {
        var pivots = List.of(pivot(100.0), pivot(200.0));
        builder.buildSRLevels(pivots, 0.01)
               .forEach(l -> assertEquals("HORIZONTAL_SR", l.getSourceType()));
    }
}
