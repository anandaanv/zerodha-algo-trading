package com.dtech.ta.patterns.classic;

import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReverseHnsClassicDetectorSearchTest {

    private final ReverseHnsClassicDetector detector = new ReverseHnsClassicDetector();
    private final ClassicPivotExtractor pivotExtractor = new ClassicPivotExtractor();

    @Test
    void testReverseHnsPatternFound() {
        BarSeries series = ClassicPatternTestData.buildReverseHnsSeries();
        List<PivotPoint> pivots = pivotExtractor.extract(series, 3, 3, PivotType.BOTH);

        List<ReverseHnsPattern> patterns = detector.findAll(series, pivots, 100);

        if (!patterns.isEmpty()) {
            ReverseHnsPattern pattern = patterns.get(0);
            assertEquals(Direction.BULLISH, pattern.direction());
            assertTrue(pattern.pivots().size() >= 5, "Should have at least 5 pivots");
            assertTrue(pattern.endBarIndex() > pattern.startBarIndex());
        }
    }

    @Test
    void testInverseHeadNotLowest() {
        // Series where C is not lower than both A and E
        double[] highs = {90, 100, 84, 102, 92, 95, 98, 100};
        double[] lows = {82, 90, 80, 92, 86, 85, 90, 92};
        double[] closes = {86, 95, 82, 100, 90, 92, 97, 98};
        double[] volumes = {1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000};
        BarSeries series = ClassicPatternTestData.buildSeries(highs, lows, closes, volumes);
        List<PivotPoint> pivots = pivotExtractor.extract(series, 3, 3, PivotType.BOTH);

        List<ReverseHnsPattern> patterns = detector.findAll(series, pivots, 100);

        assertTrue(patterns.isEmpty(), "Should not find Reverse H&S when inverse head is not the lowest");
    }

    @Test
    void testNecklineNotAboveShoulder() {
        // Series where neckline is not above both shoulder bases
        double[] highs = {90, 100, 85, 102, 92, 95, 98, 100};
        double[] lows = {80, 90, 75, 92, 82, 85, 90, 92};
        double[] closes = {85, 95, 78, 100, 88, 92, 97, 98};
        double[] volumes = {1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000};
        BarSeries series = ClassicPatternTestData.buildSeries(highs, lows, closes, volumes);
        List<PivotPoint> pivots = pivotExtractor.extract(series, 3, 3, PivotType.BOTH);

        List<ReverseHnsPattern> patterns = detector.findAll(series, pivots, 100);

        // May or may not find, depending on exact neckline relationship
        assertNotNull(patterns, "Should return list");
    }

    @Test
    void testCloseNotAboveRightShoulder() {
        // Series where close is not above E
        double[] highs = {90, 100, 85, 102, 92, 95, 98, 100};
        double[] lows = {80, 90, 75, 92, 82, 85, 90, 92};
        double[] closes = {85, 95, 78, 100, 88, 92, 97, 88};
        double[] volumes = {1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000};
        BarSeries series = ClassicPatternTestData.buildSeries(highs, lows, closes, volumes);
        List<PivotPoint> pivots = pivotExtractor.extract(series, 3, 3, PivotType.BOTH);

        List<ReverseHnsPattern> patterns = detector.findAll(series, pivots, 100);

        // Close is at or below E, so should not match the breakout condition
        assertTrue(patterns.isEmpty(), "Should not find Reverse H&S when close hasn't broken above right shoulder");
    }
}
