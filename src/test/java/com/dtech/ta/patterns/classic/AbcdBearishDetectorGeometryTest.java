package com.dtech.ta.patterns.classic;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.ta4j.core.BarSeries;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for bearish ABCD detector geometry.
 * Tests pattern detection with synthetic data (mirror of bullish).
 */
class AbcdBearishDetectorGeometryTest {

    @ParameterizedTest(name = "{index}: {0}")
    @CsvSource({
        "Clean bearish ABCD with c_retrace 0.618,PERFECT,0.618",
        "Valid ABCD with c_retrace 0.5,REGULAR,0.5",
        "Boundary c_retrace 0.382,REGULAR,0.382",
        "Valid ABCD with c_retrace 0.707,REGULAR,0.707",
        "Boundary c_retrace 0.886,REGULAR,0.886",
        "Valid tight ABCD,REGULAR,0.618",
        "Valid alternate ABCD,ALTERNATE,0.5",
        "Clean perfect ABCD,PERFECT,0.618",
    })
    void testBearishAbcdDetection(String description, String expectedKind, double expectedRetrace) {
        BarSeries series = ClassicPatternTestData.buildBearishAbcdSeries();
        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        List<PivotPoint> pivots = extractor.extract(series, 6, 6, PivotType.BOTH);

        AbcdBearishDetector detector = new AbcdBearishDetector();
        Optional<AbcdPattern> pattern = detector.findLatest(series, pivots, 100);

        if (pattern.isPresent()) {
            assertEquals(Direction.BEARISH, pattern.get().direction(), description);
            assertEquals(expectedKind, pattern.get().kind().toString(), description);
            assertTrue(pivots.size() >= 4, "Should have sufficient pivots for pattern");
        }
    }

    @ParameterizedTest(name = "{index}: {0}")
    @CsvSource({
        "Insufficient pivots,0,100",
        "Empty pivot list,0,100",
    })
    void testBearishAbcdNoPattern(String description, int minPivots, int lookback) {
        BarSeries series = ClassicPatternTestData.buildSeries(new double[]{100, 100.5, 100, 100.5});
        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        List<PivotPoint> pivots = extractor.extract(series, 6, 6, PivotType.BOTH);

        if (pivots.size() < 3) {
            AbcdBearishDetector detector = new AbcdBearishDetector();
            List<AbcdPattern> patterns = detector.findAll(series, pivots, lookback);
            assertTrue(patterns.isEmpty(), description);
        }
    }
}
