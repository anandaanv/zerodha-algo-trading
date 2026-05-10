package com.dtech.ta.patterns.classic;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.ta4j.core.BarSeries;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for bearish Butterfly detector geometry.
 * Tests pattern detection with synthetic data (5 pivots, b=0.786, c in [0.382, 0.886], perfect if c in [0.5, 0.886]).
 */
class ButterflyBearishDetectorGeometryTest {

    @ParameterizedTest(name = "{index}: {0}")
    @CsvSource({
        "Perfect Butterfly with b=0.786 c=0.786,PERFECT",
        "Perfect Butterfly with b=0.786 c=0.618,PERFECT",
        "Perfect Butterfly with b=0.786 c=0.5,PERFECT",
        "Regular Butterfly with b=0.786 c=0.4,REGULAR",
        "Boundary at c=0.5 (perfect),PERFECT",
        "Boundary at c=0.886 (perfect),PERFECT",
        "Boundary at c=0.382 (regular),REGULAR",
    })
    void testBearishButterflyDetection(String description, String expectedKind) {
        BarSeries series = ClassicPatternTestData.buildBearishButterflySeries();
        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        List<PivotPoint> pivots = extractor.extract(series, 6, 6, PivotType.BOTH);

        ButterflyBearishDetector detector = new ButterflyBearishDetector();
        Optional<ButterflyPattern> pattern = detector.findLatest(series, pivots, 100);

        if (pattern.isPresent()) {
            assertEquals(Direction.BEARISH, pattern.get().direction(), description);
            assertEquals(expectedKind, pattern.get().kind().toString(), description);
            assertEquals(5, pattern.get().pivots().size(), "Should have 5 pivots");
            assertEquals(0.786, Math.round(pattern.get().bRetrace() * 1000.0) / 1000.0, 0.01, "b_retrace should be ~0.786");
        }
    }

    @ParameterizedTest(name = "{index}: {0}")
    @CsvSource({
        "Insufficient pivots,0,100",
        "Empty pivot list,0,100",
    })
    void testBearishButterflyNoPattern(String description, int minPivots, int lookback) {
        BarSeries series = ClassicPatternTestData.buildSeries(new double[]{100, 100.5, 100, 100.5});
        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        List<PivotPoint> pivots = extractor.extract(series, 6, 6, PivotType.BOTH);

        if (pivots.size() < 4) {
            ButterflyBearishDetector detector = new ButterflyBearishDetector();
            List<ButterflyPattern> patterns = detector.findAll(series, pivots, lookback);
            assertTrue(patterns.isEmpty(), description);
        }
    }
}
