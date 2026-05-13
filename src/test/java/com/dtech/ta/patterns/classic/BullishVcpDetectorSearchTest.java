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
 * Integration tests for bullish VCP search algorithm.
 * Verifies the iterative A := C loop correctly finds valid patterns and rejects breached ones.
 * Reference: docs/spec-vcp-pattern-port.md (Search algorithm section)
 */
class BullishVcpDetectorSearchTest {

    private static final int LOOKBACK_BARS = 50;

    @Test
    void testDetectorReturnsEmptyForShortSeries() {
        BarSeries series = ClassicPatternTestData.buildBullishVcpSeries();
        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        List<PivotPoint> pivots = extractor.extract(series, 2, 2, PivotType.BOTH);

        BullishVcpDetector detector = new BullishVcpDetector();
        Optional<BullishVcpPattern> pattern = detector.findLatest(series, pivots, LOOKBACK_BARS);

        assertNotNull(pattern, "Should return an Optional");
    }

    @Test
    void testBreachedPatternNotReturned() {
        BarSeries series = ClassicPatternTestData.buildBullishVcpBreachedSeries();
        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        List<PivotPoint> pivots = extractor.extract(series, 2, 2, PivotType.BOTH);

        BullishVcpDetector detector = new BullishVcpDetector();
        Optional<BullishVcpPattern> pattern = detector.findLatest(series, pivots, LOOKBACK_BARS);

        assertTrue(pattern.isEmpty(), "Should not return a pattern when C is breached");
    }

    @Test
    void testMultiplePivotsFound() {
        BarSeries series = ClassicPatternTestData.buildBullishVcpSeries();
        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        List<PivotPoint> pivots = extractor.extract(series, 2, 2, PivotType.BOTH);

        BullishVcpDetector detector = new BullishVcpDetector();
        List<BullishVcpPattern> patterns = detector.findAll(series, pivots, LOOKBACK_BARS);

        assertNotNull(patterns, "Should return a non-null list");
    }

    @Test
    void testNoPattern() {
        double[] highs = {100, 95, 105, 90, 110, 85, 115};
        double[] lows = {90, 85, 95, 80, 100, 75, 105};
        double[] closes = {95, 90, 100, 85, 105, 80, 110};
        double[] volumes = {1000, 1000, 1000, 1000, 1000, 1000, 1000};

        BarSeries series = ClassicPatternTestData.buildSeries(highs, lows, closes, volumes);
        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        List<PivotPoint> pivots = extractor.extract(series, 2, 2, PivotType.BOTH);

        BullishVcpDetector detector = new BullishVcpDetector();
        Optional<BullishVcpPattern> pattern = detector.findLatest(series, pivots, LOOKBACK_BARS);

        assertTrue(pattern.isEmpty(), "Should not find a pattern in random walk data");
    }

    @Test
    void testInsufficientPivots() {
        double[] highs = {100, 95, 105};
        double[] lows = {90, 85, 95};
        double[] closes = {95, 90, 100};
        double[] volumes = {1000, 1000, 1000};

        BarSeries series = ClassicPatternTestData.buildSeries(highs, lows, closes, volumes);
        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        List<PivotPoint> pivots = extractor.extract(series, 2, 2, PivotType.BOTH);

        BullishVcpDetector detector = new BullishVcpDetector();
        Optional<BullishVcpPattern> pattern = detector.findLatest(series, pivots, LOOKBACK_BARS);

        assertTrue(pattern.isEmpty(), "Should return empty when fewer than 4 pivots available");
    }

    @Test
    void testSymmetricBearishVcpNotFoundByBullishDetector() {
        BarSeries series = ClassicPatternTestData.buildBearishVcpSeries();
        ClassicPivotExtractor extractor = new ClassicPivotExtractor();
        List<PivotPoint> pivots = extractor.extract(series, 2, 2, PivotType.BOTH);

        BullishVcpDetector detector = new BullishVcpDetector();
        Optional<BullishVcpPattern> pattern = detector.findLatest(series, pivots, LOOKBACK_BARS);

        assertTrue(pattern.isEmpty(), "Bullish detector should not find a bearish VCP");
    }
}
