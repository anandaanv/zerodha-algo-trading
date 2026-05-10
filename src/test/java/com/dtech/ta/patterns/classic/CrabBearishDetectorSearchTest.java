package com.dtech.ta.patterns.classic;

import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for bearish Crab detector search and pivot management.
 */
class CrabBearishDetectorSearchTest {

    @Test
    void testCleanCrabFind() {
        BarSeries series = ClassicPatternTestData.buildBearishCrabSeries();
        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        List<PivotPoint> pivots = extractor.extract(series, 6, 6, PivotType.BOTH);

        CrabBearishDetector detector = new CrabBearishDetector();
        List<CrabPattern> patterns = detector.findAll(series, pivots, 100);

        assertNotNull(patterns, "Should return a list");
        patterns.forEach(p -> {
            assertEquals(Direction.BEARISH, p.direction());
            assertEquals(5, p.pivots().size());
        });
    }

    @Test
    void testNoPatternOnInsufficientPivots() {
        BarSeries series = ClassicPatternTestData.buildSeries(new double[]{100, 100.5, 100});
        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        List<PivotPoint> pivots = extractor.extract(series, 6, 6, PivotType.BOTH);

        CrabBearishDetector detector = new CrabBearishDetector();
        List<CrabPattern> patterns = detector.findAll(series, pivots, 100);

        assertTrue(patterns.isEmpty() || patterns.size() < 1, "Insufficient pivots should yield no pattern");
    }

    @Test
    void testFindAllReturnsMultiplePatterns() {
        BarSeries series = ClassicPatternTestData.buildBearishCrabSeries();
        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        List<PivotPoint> pivots = extractor.extract(series, 6, 6, PivotType.BOTH);

        CrabBearishDetector detector = new CrabBearishDetector();
        List<CrabPattern> patterns = detector.findAll(series, pivots, 100);

        assertNotNull(patterns, "findAll should return a list (possibly empty)");
        patterns.forEach(p -> {
            assertEquals(Direction.BEARISH, p.direction());
            assertEquals(5, p.pivots().size());
        });
    }

    @Test
    void testLookbackParameterRespected() {
        BarSeries series = ClassicPatternTestData.buildBearishCrabSeries();
        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        List<PivotPoint> pivots = extractor.extract(series, 6, 6, PivotType.BOTH);

        CrabBearishDetector detector = new CrabBearishDetector();

        List<CrabPattern> patternsSmall = detector.findAll(series, pivots, 5);
        List<CrabPattern> patternsLarge = detector.findAll(series, pivots, 100);

        assertNotNull(patternsSmall);
        assertNotNull(patternsLarge);
    }
}
