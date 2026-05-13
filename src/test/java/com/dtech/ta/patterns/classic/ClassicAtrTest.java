package com.dtech.ta.patterns.classic;

import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;

import static org.junit.jupiter.api.Assertions.*;

class ClassicAtrTest {

    @Test
    void testConstantHighLowForFifteenBars() {
        // Series with constant high-low = 1.0 (synthetic data produces this)
        double[] closes = new double[20];
        for (int i = 0; i < closes.length; i++) {
            closes[i] = 100.0;
        }
        BarSeries series = ClassicPatternTestData.buildSeries(closes);

        // At bar 14 (0-indexed), we have 15 bars from 0-14
        // Synthetic highs = close*1.01 = 101, lows = close*0.99 = 99
        // Each bar TR = 101 - 99 = 2.0
        // Mean over 15 bars = 2.0
        double atr = ClassicAtr.mean(series, 14, 15);

        assertEquals(2.0, atr, 0.001);
    }

    @Test
    void testNotEnoughBarsThrows() {
        double[] closes = {100.0, 101.0, 102.0};
        BarSeries series = ClassicPatternTestData.buildSeries(closes);

        assertThrows(IllegalArgumentException.class, () -> {
            ClassicAtr.mean(series, 1, 15);
        }, "Should throw if barIndex < window");
    }

    @Test
    void testDefaultWindow() {
        double[] closes = new double[20];
        for (int i = 0; i < closes.length; i++) {
            closes[i] = 100.0;
        }
        BarSeries series = ClassicPatternTestData.buildSeries(closes);

        // Default window = 15
        double atr = ClassicAtr.mean(series, 15);

        assertEquals(2.0, atr, 0.001);
    }

    @Test
    void testVaryingHighLow() {
        // Series with varying ranges to test TR calculation
        double[] closes = {100.0, 101.0, 99.0, 102.0, 98.0, 103.0};
        BarSeries series = ClassicPatternTestData.buildSeries(closes);

        // All bars have synthetic high-low = 2.0
        // TR = max(2.0, |high - prevClose|, |low - prevClose|)
        double atr = ClassicAtr.mean(series, 5, 5);

        assertTrue(atr > 0, "ATR should be positive");
    }

    @Test
    void testAtBarZeroWithPrevClose() {
        // Bar 0 has no previous close, so TR = high - low only
        double[] closes = {100.0, 101.0, 102.0};
        BarSeries series = ClassicPatternTestData.buildSeries(closes);

        // At bar 0, TR = high - low = 2.0
        // At bar 1, TR = max(2.0, |101*1.01 - 100|, |101*0.99 - 100|)
        //              = max(2.0, 1.01, 0.99) = 2.0
        double atr = ClassicAtr.mean(series, 1, 2);

        assertTrue(atr > 0, "ATR at bar 1 with window 2 should be positive");
    }
}
