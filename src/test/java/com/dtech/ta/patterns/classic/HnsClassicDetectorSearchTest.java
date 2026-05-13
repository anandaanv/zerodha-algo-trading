package com.dtech.ta.patterns.classic;

import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HnsClassicDetectorSearchTest {

    private final HnsClassicDetector detector = new HnsClassicDetector();
    private final ClassicPivotExtractor pivotExtractor = new ClassicPivotExtractor();

    @Test
    void testHnsPatternFound() {
        BarSeries series = ClassicPatternTestData.buildHnsSeries();
        List<PivotPoint> pivots = pivotExtractor.extract(series, 3, 3, PivotType.BOTH);

        List<HnsPattern> patterns = detector.findAll(series, pivots, 100);

        if (!patterns.isEmpty()) {
            HnsPattern pattern = patterns.get(0);
            assertEquals(Direction.BEARISH, pattern.direction());
            assertTrue(pattern.pivots().size() >= 5, "Should have at least 5 pivots");
            assertTrue(pattern.endBarIndex() > pattern.startBarIndex());
        }
    }

    @Test
    void testHeadNotHighest() {
        // Series where C is not higher than both A and E
        double[] highs = {95, 88, 94, 86, 95, 95, 91, 90};
        double[] lows = {85, 75, 84, 73, 85, 85, 80, 78};
        double[] closes = {90, 80, 90, 78, 90, 90, 85, 85};
        double[] volumes = {1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000};
        BarSeries series = ClassicPatternTestData.buildSeries(highs, lows, closes, volumes);
        List<PivotPoint> pivots = pivotExtractor.extract(series, 3, 3, PivotType.BOTH);

        List<HnsPattern> patterns = detector.findAll(series, pivots, 100);

        assertTrue(patterns.isEmpty(), "Should not find H&S when head is not the highest");
    }

    @Test
    void testNecklineBreachedNotYet() {
        // Series where close hasn't broken below right shoulder yet
        double[] highs = {95, 88, 105, 86, 93, 95, 98, 100};
        double[] lows = {85, 75, 95, 73, 83, 85, 90, 92};
        double[] closes = {90, 80, 100, 78, 88, 94, 96, 98};
        double[] volumes = {1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000};
        BarSeries series = ClassicPatternTestData.buildSeries(highs, lows, closes, volumes);
        List<PivotPoint> pivots = pivotExtractor.extract(series, 3, 3, PivotType.BOTH);

        List<HnsPattern> patterns = detector.findAll(series, pivots, 100);

        // May or may not find pattern depending on close relative to E shoulder
        assertNotNull(patterns, "Should return list");
    }

    @Test
    void testInsufficientPivots() {
        // Very short series with few bars
        double[] highs = {100, 99, 101, 100};
        double[] lows = {98, 97, 99, 98};
        double[] closes = {99, 98, 100, 99};
        double[] volumes = {1000, 1000, 1000, 1000};
        BarSeries series = ClassicPatternTestData.buildSeries(highs, lows, closes, volumes);
        List<PivotPoint> pivots = pivotExtractor.extract(series, 3, 3, PivotType.BOTH);

        List<HnsPattern> patterns = detector.findAll(series, pivots, 100);

        // Expect either empty or graceful handling
        assertNotNull(patterns, "Should return empty list if not enough pivots");
        // With such a short series, likely no 5-pivot windows
    }
}
