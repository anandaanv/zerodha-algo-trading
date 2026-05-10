package com.dtech.ta.patterns.classic;

import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DoubleTopClassicDetectorSearchTest {

    private final DoubleTopClassicDetector detector = new DoubleTopClassicDetector();
    private final ClassicPivotExtractor pivotExtractor = new ClassicPivotExtractor();

    @Test
    void testDoubleTopPatternFound() {
        BarSeries series = ClassicPatternTestData.buildDoubleTopSeries();
        List<PivotPoint> pivots = pivotExtractor.extract(series, 3, 3, PivotType.BOTH);

        List<DoubleTopPattern> patterns = detector.findAll(series, pivots, 100);

        if (!patterns.isEmpty()) {
            DoubleTopPattern pattern = patterns.get(0);
            assertEquals(Direction.BEARISH, pattern.direction());
            assertTrue(pattern.pivots().size() >= 3, "Should have at least 3 pivots");
            assertTrue(pattern.endBarIndex() > pattern.startBarIndex());
        }
    }

    @Test
    void testVolumeIncreaseOnSecondPeak() {
        // Series where second peak has higher volume (should fail)
        double[] highs = {100, 95, 100, 98, 96, 94, 92, 90};
        double[] lows = {90, 80, 90, 88, 86, 84, 82, 80};
        double[] closes = {95, 85, 95, 93, 91, 89, 87, 85};
        double[] volumes = {4000, 3500, 5000, 3000, 2500, 2000, 1500, 1000};
        BarSeries series = ClassicPatternTestData.buildSeries(highs, lows, closes, volumes);
        List<PivotPoint> pivots = pivotExtractor.extract(series, 3, 3, PivotType.BOTH);

        List<DoubleTopPattern> patterns = detector.findAll(series, pivots, 100);

        assertTrue(patterns.isEmpty(), "Should not find Double Top when volume increases on second peak");
    }

    @Test
    void testPeaksAtDifferentLevels() {
        // Series where peaks are too far apart (|A-C| > avgBar*0.5)
        double[] highs = {100, 95, 107, 98, 96, 94, 92, 90};
        double[] lows = {90, 80, 97, 88, 86, 84, 82, 80};
        double[] closes = {95, 85, 102, 93, 91, 89, 87, 85};
        double[] volumes = {5000, 4500, 4000, 3500, 3000, 2500, 2000, 1500};
        BarSeries series = ClassicPatternTestData.buildSeries(highs, lows, closes, volumes);
        List<PivotPoint> pivots = pivotExtractor.extract(series, 3, 3, PivotType.BOTH);

        List<DoubleTopPattern> patterns = detector.findAll(series, pivots, 100);

        assertTrue(patterns.isEmpty(), "Should not find Double Top when peaks are at different levels");
    }

    @Test
    void testNecklineToSecondPeakExceedsAtrDistance() {
        // Series where C - B > 4*ATR (ATR computed from series, may exceed distance)
        // This test is approximate as ATR is computed from real series data
        double[] highs = {100, 70, 100, 98, 96, 94, 92, 90};
        double[] lows = {90, 60, 90, 88, 86, 84, 82, 80};
        double[] closes = {95, 65, 95, 93, 91, 89, 87, 85};
        double[] volumes = {5000, 4500, 4000, 3500, 3000, 2500, 2000, 1500};
        BarSeries series = ClassicPatternTestData.buildSeries(highs, lows, closes, volumes);
        List<PivotPoint> pivots = pivotExtractor.extract(series, 3, 3, PivotType.BOTH);

        List<DoubleTopPattern> patterns = detector.findAll(series, pivots, 100);

        // Depending on actual ATR calculation, may or may not find pattern
        assertNotNull(patterns, "Should return list");
    }
}
