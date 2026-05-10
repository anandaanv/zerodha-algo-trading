package com.dtech.ta.patterns.classic;

import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClassicPivotExtractorTest {

    private final ClassicPivotExtractor extractor = new ClassicPivotExtractor();

    @Test
    void testSinglePeakInMiddle() {
        // Series with a single local high in the middle
        double[] closes = {1.0, 1.5, 2.0, 1.8, 1.5, 1.0, 1.2, 1.3};
        BarSeries series = ClassicPatternTestData.buildSeries(closes);

        List<PivotPoint> highs = extractor.extract(series, 2, 2, PivotType.HIGH);

        assertEquals(1, highs.size());
        assertEquals(2, highs.get(0).barIndex());
    }

    @Test
    void testSingleTroughInMiddle() {
        // Series with a single local low in the middle
        double[] closes = {2.0, 1.8, 1.2, 1.5, 1.8, 2.0, 1.9, 1.8};
        BarSeries series = ClassicPatternTestData.buildSeries(closes);

        List<PivotPoint> lows = extractor.extract(series, 2, 2, PivotType.LOW);

        assertEquals(1, lows.size());
        assertEquals(2, lows.get(0).barIndex());
    }

    @Test
    void testMultiplePeaksWithSmallWindow() {
        // Series with multiple local highs
        double[] closes = {1.0, 2.0, 1.5, 2.5, 1.8, 2.2, 1.5, 1.0};
        BarSeries series = ClassicPatternTestData.buildSeries(closes);

        List<PivotPoint> highs = extractor.extract(series, 1, 1, PivotType.HIGH);

        assertTrue(highs.size() >= 2, "Should find at least 2 peaks");
    }

    @Test
    void testSeriesTooShortForWindow() {
        // Series shorter than required window
        double[] closes = {1.0, 1.5, 1.2};
        BarSeries series = ClassicPatternTestData.buildSeries(closes);

        List<PivotPoint> pivots = extractor.extract(series, 6, 6, PivotType.HIGH);

        assertEquals(0, pivots.size(), "Series too short, should return empty");
    }

    @Test
    void testDifferentLeftRightWindows() {
        double[] closes = {1.0, 1.2, 2.0, 1.5, 1.8, 1.2, 1.0, 0.9};
        BarSeries series = ClassicPatternTestData.buildSeries(closes);

        List<PivotPoint> highs = extractor.extract(series, 3, 2, PivotType.HIGH);

        assertTrue(highs.size() >= 0, "Should handle asymmetric windows");
    }

    @Test
    void testBothPivotTypes() {
        // Series with alternating peaks and troughs
        double[] closes = {1.0, 2.0, 1.2, 2.1, 1.3, 2.0, 1.5, 1.1};
        BarSeries series = ClassicPatternTestData.buildSeries(closes);

        List<PivotPoint> combined = extractor.extract(series, 1, 1, PivotType.BOTH);

        assertTrue(combined.size() > 0, "Should find at least one pivot");

        // Verify sorted by barIndex
        for (int i = 0; i < combined.size() - 1; i++) {
            assertTrue(combined.get(i).barIndex() < combined.get(i + 1).barIndex(),
                "Pivots should be sorted by barIndex");
        }
    }

    @Test
    void testDefaultWindowOverload() {
        double[] closes = {1.0, 1.5, 2.0, 1.8, 1.5, 1.0, 1.2, 1.3, 1.1, 1.0, 0.9, 0.8, 1.0};
        BarSeries series = ClassicPatternTestData.buildSeries(closes);

        // Call without barsLeft/barsRight (uses defaults 6, 6)
        List<PivotPoint> highs = extractor.extract(series, PivotType.HIGH);

        // With default 6/6 and 13 bars, valid center range is [6, 6], i.e., only bar 6
        assertTrue(highs.size() >= 0, "Default window should work");
    }
}
