package com.dtech.ta.patterns.classic;

import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;

import static org.junit.jupiter.api.Assertions.*;

class AvgBarLengthTest {

    @Test
    void testSingleBar() {
        // Single bar with high-low = 1.0
        double[] closes = {100.0};
        BarSeries series = ClassicPatternTestData.buildSeries(closes);

        double avgBarLength = AvgBarLength.median(series, 0, 0);

        // Synthetic range: high = 100*1.01 = 101, low = 100*0.99 = 99
        // range = 2.0
        assertEquals(2.0, avgBarLength, 0.001);
    }

    @Test
    void testTwoEqualBars() {
        double[] closes = {100.0, 100.0};
        BarSeries series = ClassicPatternTestData.buildSeries(closes);

        double avgBarLength = AvgBarLength.median(series, 0, 1);

        // Both bars have same range 2.0, median = 2.0
        assertEquals(2.0, avgBarLength, 0.001);
    }

    @Test
    void testThreeBarsOddCount() {
        double[] closes = {100.0, 101.0, 99.0};
        BarSeries series = ClassicPatternTestData.buildSeries(closes);

        double avgBarLength = AvgBarLength.median(series, 0, 2);

        // Ranges: [2.0, 2.02, 1.98], sorted: [1.98, 2.0, 2.02]
        // median (odd count) = middle value = 2.0
        assertEquals(2.0, avgBarLength, 0.01);
    }

    @Test
    void testFourBarsEvenCount() {
        double[] closes = {100.0, 101.0, 102.0, 103.0};
        BarSeries series = ClassicPatternTestData.buildSeries(closes);

        double avgBarLength = AvgBarLength.median(series, 0, 3);

        // All have similar range ~2.0, median of even count averages two middle values
        assertTrue(AvgBarLength.median(series, 0, 3) > 1.99 && AvgBarLength.median(series, 0, 3) < 2.05,
            "Median of 4 bars should be around 2.0");
    }

    @Test
    void testFromIndexGreaterThanToIndex() {
        double[] closes = {100.0, 101.0, 102.0};
        BarSeries series = ClassicPatternTestData.buildSeries(closes);

        assertThrows(IllegalArgumentException.class, () -> {
            AvgBarLength.median(series, 2, 0);
        });
    }

    @Test
    void testIndexOutOfBounds() {
        double[] closes = {100.0, 101.0};
        BarSeries series = ClassicPatternTestData.buildSeries(closes);

        assertThrows(IllegalArgumentException.class, () -> {
            AvgBarLength.median(series, 0, 5);
        });
    }

    @Test
    void testNegativeIndexOutOfBounds() {
        double[] closes = {100.0, 101.0};
        BarSeries series = ClassicPatternTestData.buildSeries(closes);

        assertThrows(IllegalArgumentException.class, () -> {
            AvgBarLength.median(series, -1, 1);
        });
    }
}
