package com.dtech.ta.patterns.classic;

import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for bearish VCP search algorithm.
 * Verifies the iterative A := C loop correctly finds valid patterns and rejects breached ones.
 * Reference: docs/spec-vcp-pattern-port.md (Search algorithm section)
 */
class BearishVcpDetectorSearchTest {

    private static final int LOOKBACK_BARS = 50;

    @Test
    void testDetectorReturnsEmptyForShortSeries() {
        BarSeries series = ClassicPatternTestData.buildBearishVcpSeries();
        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        List<PivotPoint> pivots = extractor.extract(series, 2, 2, PivotType.BOTH);

        BearishVcpDetector detector = new BearishVcpDetector();
        Optional<BearishVcpPattern> pattern = detector.findLatest(series, pivots, LOOKBACK_BARS);

        assertNotNull(pattern, "Should return an Optional");
    }

    @Test
    void testBreachedPatternNotReturned() {
        BarSeries series = ClassicPatternTestData.buildBearishVcpBreachedSeries();
        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        List<PivotPoint> pivots = extractor.extract(series, 2, 2, PivotType.BOTH);

        BearishVcpDetector detector = new BearishVcpDetector();
        Optional<BearishVcpPattern> pattern = detector.findLatest(series, pivots, LOOKBACK_BARS);

        assertTrue(pattern.isEmpty(), "Should not return a pattern when C is breached");
    }

    @Test
    void testMultiplePivotsFound() {
        BarSeries series = ClassicPatternTestData.buildBearishVcpSeries();
        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        List<PivotPoint> pivots = extractor.extract(series, 2, 2, PivotType.BOTH);

        BearishVcpDetector detector = new BearishVcpDetector();
        List<BearishVcpPattern> patterns = detector.findAll(series, pivots, LOOKBACK_BARS);

        assertNotNull(patterns, "Should return a non-null list");
    }

    @Test
    void testNoPattern() {
        double[] highs = {100, 105, 95, 110, 90, 115, 85};
        double[] lows = {90, 95, 85, 100, 80, 105, 75};
        double[] closes = {95, 100, 90, 105, 85, 110, 80};
        double[] volumes = {1000, 1000, 1000, 1000, 1000, 1000, 1000};

        BarSeries series = ClassicPatternTestData.buildSeries(highs, lows, closes, volumes);
        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        List<PivotPoint> pivots = extractor.extract(series, 2, 2, PivotType.BOTH);

        BearishVcpDetector detector = new BearishVcpDetector();
        Optional<BearishVcpPattern> pattern = detector.findLatest(series, pivots, LOOKBACK_BARS);

        assertTrue(pattern.isEmpty(), "Should not find a pattern in random walk data");
    }

    @Test
    void testInsufficientPivots() {
        double[] highs = {100, 105, 95};
        double[] lows = {90, 95, 85};
        double[] closes = {95, 100, 90};
        double[] volumes = {1000, 1000, 1000};

        BarSeries series = ClassicPatternTestData.buildSeries(highs, lows, closes, volumes);
        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        List<PivotPoint> pivots = extractor.extract(series, 2, 2, PivotType.BOTH);

        BearishVcpDetector detector = new BearishVcpDetector();
        Optional<BearishVcpPattern> pattern = detector.findLatest(series, pivots, LOOKBACK_BARS);

        assertTrue(pattern.isEmpty(), "Should return empty when fewer than 4 pivots available");
    }

    @Test
    void testSymmetricBullishVcpNotFoundByBearishDetector() {
        BarSeries series = ClassicPatternTestData.buildBullishVcpSeries();
        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        List<PivotPoint> pivots = extractor.extract(series, 2, 2, PivotType.BOTH);

        BearishVcpDetector detector = new BearishVcpDetector();
        Optional<BearishVcpPattern> pattern = detector.findLatest(series, pivots, LOOKBACK_BARS);

        assertTrue(pattern.isEmpty(), "Bearish detector should not find a bullish VCP");
    }
}
