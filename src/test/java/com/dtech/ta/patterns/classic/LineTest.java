package com.dtech.ta.patterns.classic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LineTest {

    @Test
    void testHorizontalLine() {
        // Horizontal line: slope = 0, yIntercept = 50
        Line line = new Line(0, 50, 0, 50, 10, 50);

        // valueAt any x should equal yIntercept
        assertEquals(50.0, line.valueAt(0), 0.0001);
        assertEquals(50.0, line.valueAt(5), 0.0001);
        assertEquals(50.0, line.valueAt(10), 0.0001);
    }

    @Test
    void testSlopeOneZeroIntercept() {
        // Line: y = x
        // slope = 1, yIntercept = 0
        Line line = new Line(1, 0, 0, 0, 10, 10);

        assertEquals(0.0, line.valueAt(0), 0.0001);
        assertEquals(5.0, line.valueAt(5), 0.0001);
        assertEquals(10.0, line.valueAt(10), 0.0001);
    }

    @Test
    void testNegativeSlopePositiveIntercept() {
        // Line: y = -2x + 10
        // slope = -2, yIntercept = 10
        Line line = new Line(-2, 10, 0, 10, 5, 0);

        assertEquals(10.0, line.valueAt(0), 0.0001);
        assertEquals(6.0, line.valueAt(2), 0.0001);
        assertEquals(4.0, line.valueAt(3), 0.0001);
        assertEquals(0.0, line.valueAt(5), 0.0001);
    }

    @Test
    void testPositiveSlopePositiveIntercept() {
        // Line: y = 2x + 5
        // slope = 2, yIntercept = 5
        Line line = new Line(2, 5, 0, 5, 10, 25);

        assertEquals(5.0, line.valueAt(0), 0.0001);
        assertEquals(9.0, line.valueAt(2), 0.0001);
        assertEquals(15.0, line.valueAt(5), 0.0001);
        assertEquals(25.0, line.valueAt(10), 0.0001);
    }
}
