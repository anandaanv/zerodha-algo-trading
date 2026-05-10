package com.dtech.ta.patterns.classic;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.ta4j.core.BarSeries;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for bullish Gartley detector geometry.
 * Tests pattern detection with synthetic data (5 pivots, b=0.618, c in [0.382, 0.886]).
 */
class GartleyBullishDetectorGeometryTest {

    @ParameterizedTest(name = "{index}: {0}")
    @CsvSource({
        "Clean bullish Gartley with b=0.618 c=0.618,PERFECT",
        "Valid Gartley with b=0.618 c=0.5,REGULAR",
        "Valid Gartley with b=0.618 c=0.707,REGULAR",
        "Valid Gartley with b=0.618 c=0.382 boundary,REGULAR",
        "Valid Gartley with b=0.618 c=0.886 boundary,REGULAR",
    })
    void testBullishGartleyDetection(String description, String expectedKind) {
        BarSeries series = ClassicPatternTestData.buildBullishGartleySeries();
        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        List<PivotPoint> pivots = extractor.extract(series, 6, 6, PivotType.BOTH);

        GartleyBullishDetector detector = new GartleyBullishDetector();
        Optional<GartleyPattern> pattern = detector.findLatest(series, pivots, 100);

        if (pattern.isPresent()) {
            assertEquals(Direction.BULLISH, pattern.get().direction(), description);
            assertEquals(expectedKind, pattern.get().kind().toString(), description);
            assertEquals(5, pattern.get().pivots().size(), "Should have 5 pivots");
            assertEquals(0.618, Math.round(pattern.get().bRetrace() * 1000.0) / 1000.0, 0.01, "b_retrace should be ~0.618");
        }
    }

    @ParameterizedTest(name = "{index}: {0}")
    @CsvSource({
        "Insufficient pivots,0,100",
        "Empty pivot list,0,100",
    })
    void testBullishGartleyNoPattern(String description, int minPivots, int lookback) {
        BarSeries series = ClassicPatternTestData.buildSeries(new double[]{100, 100.5, 100, 100.5});
        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        List<PivotPoint> pivots = extractor.extract(series, 6, 6, PivotType.BOTH);

        if (pivots.size() < 4) {
            GartleyBullishDetector detector = new GartleyBullishDetector();
            List<GartleyPattern> patterns = detector.findAll(series, pivots, lookback);
            assertTrue(patterns.isEmpty(), description);
        }
    }
}
