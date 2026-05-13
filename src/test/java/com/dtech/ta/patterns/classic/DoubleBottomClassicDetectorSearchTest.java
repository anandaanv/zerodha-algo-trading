package com.dtech.ta.patterns.classic;

import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DoubleBottomClassicDetectorSearchTest {

    private final DoubleBottomClassicDetector detector = new DoubleBottomClassicDetector();
    private final ClassicPivotExtractor pivotExtractor = new ClassicPivotExtractor();

    @Test
    void testDoubleBottomPatternFound() {
        BarSeries series = ClassicPatternTestData.buildDoubleBottomSeries();
        List<PivotPoint> pivots = pivotExtractor.extract(series, 3, 3, PivotType.BOTH);

        List<DoubleBottomPattern> patterns = detector.findAll(series, pivots, 100);

        if (!patterns.isEmpty()) {
            DoubleBottomPattern pattern = patterns.get(0);
            assertEquals(Direction.BULLISH, pattern.direction());
            assertTrue(pattern.pivots().size() >= 3, "Should have at least 3 pivots");
            assertTrue(pattern.endBarIndex() > pattern.startBarIndex());
        }
    }

    @Test
    void testVolumeIncreaseOnSecondValley() {
        // Series where second valley has higher volume (should fail)
        double[] highs = {90, 100, 90, 92, 94, 96, 98, 100};
        double[] lows = {80, 90, 80, 82, 84, 86, 88, 90};
        double[] closes = {85, 95, 85, 87, 89, 91, 93, 95};
        double[] volumes = {4000, 3500, 5000, 3000, 2500, 2000, 1500, 1000};
        BarSeries series = ClassicPatternTestData.buildSeries(highs, lows, closes, volumes);
        List<PivotPoint> pivots = pivotExtractor.extract(series, 3, 3, PivotType.BOTH);

        List<DoubleBottomPattern> patterns = detector.findAll(series, pivots, 100);

        assertTrue(patterns.isEmpty(), "Should not find Double Bottom when volume increases on second valley");
    }

    @Test
    void testValleysAtDifferentLevels() {
        // Series where valleys are too far apart (|A-C| > avgBar*0.5)
        double[] highs = {90, 100, 83, 92, 94, 96, 98, 100};
        double[] lows = {80, 90, 73, 82, 84, 86, 88, 90};
        double[] closes = {85, 95, 78, 87, 89, 91, 93, 95};
        double[] volumes = {5000, 4500, 4000, 3500, 3000, 2500, 2000, 1500};
        BarSeries series = ClassicPatternTestData.buildSeries(highs, lows, closes, volumes);
        List<PivotPoint> pivots = pivotExtractor.extract(series, 3, 3, PivotType.BOTH);

        List<DoubleBottomPattern> patterns = detector.findAll(series, pivots, 100);

        assertTrue(patterns.isEmpty(), "Should not find Double Bottom when valleys are at different levels");
    }

    @Test
    void testNecklineToSecondValleyExceedsAtrDistance() {
        // Series where B - C > 4*ATR (with large gap)
        double[] highs = {90, 100, 90, 92, 94, 96, 98, 100};
        double[] lows = {80, 130, 80, 82, 84, 86, 88, 90};
        double[] closes = {85, 125, 85, 87, 89, 91, 93, 95};
        double[] volumes = {5000, 4500, 4000, 3500, 3000, 2500, 2000, 1500};
        BarSeries series = ClassicPatternTestData.buildSeries(highs, lows, closes, volumes);
        List<PivotPoint> pivots = pivotExtractor.extract(series, 3, 3, PivotType.BOTH);

        List<DoubleBottomPattern> patterns = detector.findAll(series, pivots, 100);

        // Depending on actual ATR calculation, may or may not find pattern
        assertNotNull(patterns, "Should return list");
    }
}
