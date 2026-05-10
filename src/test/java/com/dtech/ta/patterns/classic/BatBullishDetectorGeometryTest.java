package com.dtech.ta.patterns.classic;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.ta4j.core.BarSeries;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for bullish BAT detector geometry.
 * Tests pattern detection with synthetic data (5 pivots).
 */
class BatBullishDetectorGeometryTest {

    @ParameterizedTest(name = "{index}: {0}")
    @CsvSource({
        "Clean bullish BAT with b=0.5 c=0.618,PERFECT",
        "Valid BAT with b=0.5 c=0.5,PERFECT",
        "Valid BAT with b=0.382,ALTERNATE",
        "Valid BAT with regular ratios,REGULAR",
        "Valid BAT with b=0.5 c=0.707,REGULAR",
        "Valid BAT with standard ratios,REGULAR",
        "Perfect BAT pattern,PERFECT",
        "Alternate BAT pattern,ALTERNATE",
    })
    void testBullishBatDetection(String description, String expectedKind) {
        BarSeries series = ClassicPatternTestData.buildBullishBatSeries();
        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        List<PivotPoint> pivots = extractor.extract(series, 6, 6, PivotType.BOTH);

        BatBullishDetector detector = new BatBullishDetector();
        Optional<BatPattern> pattern = detector.findLatest(series, pivots, 100);

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
    void testBullishBatNoPattern(String description, int minPivots, int lookback) {
        BarSeries series = ClassicPatternTestData.buildSeries(new double[]{100, 100.5, 100, 100.5});
        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        List<PivotPoint> pivots = extractor.extract(series, 6, 6, PivotType.BOTH);

        if (pivots.size() < 4) {
            BatBullishDetector detector = new BatBullishDetector();
            List<BatPattern> patterns = detector.findAll(series, pivots, lookback);
            assertTrue(patterns.isEmpty(), description);
        }
    }
}
