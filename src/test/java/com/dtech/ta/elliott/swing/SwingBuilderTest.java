package com.dtech.ta.elliott.swing;

import com.dtech.chartpattern.zigzag.ZigZagPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SwingBuilderTest {

    private SwingBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new SwingBuilder();
    }

    private ZigZagPoint pivot(ZigZagPoint.Type type, int barIndex, double price) {
        return ZigZagPoint.builder()
                .type(type)
                .barIndex(barIndex)
                .value(price)
                .timestamp(Instant.ofEpochSecond(barIndex * 86400L))
                .build();
    }

    @Test
    void basic_up_swing() {
        var pivots = List.of(
                pivot(ZigZagPoint.Type.LOW, 2, 100.0),
                pivot(ZigZagPoint.Type.HIGH, 8, 120.0)
        );
        var result = builder.build(pivots, "INFY", "1D");
        assertEquals(1, result.size());
        var swing = result.get(0);
        assertEquals("UP", swing.getDirection());
        assertEquals(20.0, swing.getPriceChange(), 0.001);
        assertEquals(20.0, swing.getPercentChange(), 0.001);
        assertEquals(6, swing.getBarCount());
    }

    @Test
    void basic_down_swing() {
        var pivots = List.of(
                pivot(ZigZagPoint.Type.HIGH, 2, 150.0),
                pivot(ZigZagPoint.Type.LOW, 7, 120.0)
        );
        var result = builder.build(pivots, "INFY", "1D");
        assertEquals(1, result.size());
        var swing = result.get(0);
        assertEquals("DOWN", swing.getDirection());
        assertEquals(-30.0, swing.getPriceChange(), 0.001);
        assertEquals(20.0, swing.getPercentChange(), 0.001);
        assertEquals(5, swing.getBarCount());
    }

    @Test
    void multiple_swings() {
        var pivots = List.of(
                pivot(ZigZagPoint.Type.LOW, 1, 100.0),
                pivot(ZigZagPoint.Type.HIGH, 4, 130.0),
                pivot(ZigZagPoint.Type.LOW, 7, 110.0),
                pivot(ZigZagPoint.Type.HIGH, 12, 145.0)
        );
        var result = builder.build(pivots, "INFY", "1D");
        assertEquals(3, result.size());
        assertEquals("UP", result.get(0).getDirection());
        assertEquals("DOWN", result.get(1).getDirection());
        assertEquals("UP", result.get(2).getDirection());
        // All IDs unique
        long distinctIds = result.stream().map(Swing::getId).distinct().count();
        assertEquals(3, distinctIds);
    }

    @Test
    void empty_input_returns_empty() {
        var result = builder.build(List.of(), "INFY", "1D");
        assertTrue(result.isEmpty());
    }

    @Test
    void single_pivot_returns_empty() {
        var pivots = List.of(pivot(ZigZagPoint.Type.LOW, 0, 100.0));
        var result = builder.build(pivots, "INFY", "1D");
        assertTrue(result.isEmpty());
    }

    @Test
    void null_input_returns_empty() {
        var result = builder.build(null, "INFY", "1D");
        assertTrue(result.isEmpty());
    }

    @Test
    void slope_correctness() {
        var pivots = List.of(
                pivot(ZigZagPoint.Type.LOW, 0, 100.0),
                pivot(ZigZagPoint.Type.HIGH, 5, 125.0)
        );
        var result = builder.build(pivots, "X", "1D");
        assertEquals(5.0, result.get(0).getSlope(), 0.001);
    }

    @Test
    void id_format() {
        var pivots = List.of(
                pivot(ZigZagPoint.Type.LOW, 3, 100.0),
                pivot(ZigZagPoint.Type.HIGH, 9, 110.0)
        );
        var result = builder.build(pivots, "INFY", "1D");
        assertEquals("INFY_1D_3_9", result.get(0).getId());
    }
}
