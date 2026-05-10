package com.dtech.ta.patterns.classic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FibRatiosTest {

    @Test
    void testSnapToFibNearestBelow() {
        double snapped = FibRatios.snapToFib(0.4);
        assertEquals(0.382, snapped);
    }

    @Test
    void testSnapToFibNearestAbove() {
        double snapped = FibRatios.snapToFib(0.65);
        assertEquals(0.618, snapped);
    }

    @Test
    void testSnapToFibUpperBound() {
        double snapped = FibRatios.snapToFib(0.999);
        assertEquals(1.0, snapped);
    }

    @Test
    void testSnapToFibLowerBound() {
        double snapped = FibRatios.snapToFib(0.0);
        assertEquals(0.236, snapped);
    }

    @Test
    void testSnapToFibExactMatch() {
        double snapped = FibRatios.snapToFib(0.5);
        assertEquals(0.5, snapped);
    }

    @Test
    void testSnapToFibBetweenTwo() {
        // 0.75 is between 0.707 and 0.786, closer to 0.786
        double snapped = FibRatios.snapToFib(0.75);
        assertEquals(0.786, snapped);
    }

    @Test
    void testIsInRangeHappyPath() {
        assertTrue(FibRatios.isInRange(0.5, 0.3, 0.7));
    }

    @Test
    void testIsInRangeLowerBoundary() {
        assertTrue(FibRatios.isInRange(0.3, 0.3, 0.7), "Lower bound inclusive");
    }

    @Test
    void testIsInRangeUpperBoundary() {
        assertTrue(FibRatios.isInRange(0.7, 0.3, 0.7), "Upper bound inclusive");
    }

    @Test
    void testIsInRangeBelowRange() {
        assertFalse(FibRatios.isInRange(0.2, 0.3, 0.7));
    }

    @Test
    void testIsInRangeAboveRange() {
        assertFalse(FibRatios.isInRange(0.8, 0.3, 0.7));
    }

    @Test
    void testFibSeriesConstants() {
        assertEquals(8, FibRatios.FIB_SERIES.length);
        assertEquals(0.236, FibRatios.FIB_SERIES[0]);
        assertEquals(1.0, FibRatios.FIB_SERIES[7]);
    }
}
