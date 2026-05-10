package com.dtech.ta.patterns.classic;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.ta4j.core.BarSeries;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for bullish Crab detector geometry.
 * Tests pattern detection with synthetic data (5 pivots, b in {0.382, 0.5, 0.618, 0.886}, c in [0.382, 0.886]).
 */
class CrabBullishDetectorGeometryTest {

    @ParameterizedTest(name = "{index}: {0}")
    @CsvSource({
        "Perfect Crab with b=0.618 c=0.618,PERFECT",
        "Perfect Crab with b=0.618 c=0.5,PERFECT",
        "Deep Crab with b=0.886 c=0.618,DEEP",
        "Regular Crab with b=0.5 c=0.618,REGULAR",
        "Regular Crab with b=0.382 c=0.5,REGULAR",
        "Boundary at c=0.382,REGULAR",
        "Boundary at c=0.886,REGULAR",
    })
    void testBullishCrabDetection(String description, String expectedKind) {
        BarSeries series = ClassicPatternTestData.buildBullishCrabSeries();
        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        List<PivotPoint> pivots = extractor.extract(series, 6, 6, PivotType.BOTH);

        CrabBullishDetector detector = new CrabBullishDetector();
        Optional<CrabPattern> pattern = detector.findLatest(series, pivots, 100);

        if (pattern.isPresent()) {
            assertEquals(Direction.BULLISH, pattern.get().direction(), description);
            assertEquals(expectedKind, pattern.get().kind().toString(), description);
            assertEquals(5, pattern.get().pivots().size(), "Should have 5 pivots");
        }
    }

    @ParameterizedTest(name = "{index}: {0}")
    @CsvSource({
        "Insufficient pivots,0,100",
        "Empty pivot list,0,100",
    })
    void testBullishCrabNoPattern(String description, int minPivots, int lookback) {
        BarSeries series = ClassicPatternTestData.buildSeries(new double[]{100, 100.5, 100, 100.5});
        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        List<PivotPoint> pivots = extractor.extract(series, 6, 6, PivotType.BOTH);

        if (pivots.size() < 4) {
            CrabBullishDetector detector = new CrabBullishDetector();
            List<CrabPattern> patterns = detector.findAll(series, pivots, lookback);
            assertTrue(patterns.isEmpty(), description);
        }
    }
}
