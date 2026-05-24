package com.dtech.aitrader.v2.narrative.pivot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DefaultSeriesPivotEngine.
 *
 * Tests cover edge cases, zero-crossing safety, significance computation,
 * and algorithm correctness across diverse synthetic signals.
 */
class DefaultSeriesPivotEngineTest {

    private DefaultSeriesPivotEngine engine;
    private SignificanceParams params;

    @BeforeEach
    void setUp() {
        engine = new DefaultSeriesPivotEngine();
        // Use small atrLength=3 for quick convergence in tests
        params = new SignificanceParams(
            3,       // atrLength
            2.5,     // atrMult
            0.02,    // pctMin
            0.7,     // hysteresis
            3,       // minBarsBetweenPivots
            false,   // dynamicPctEnabled
            1.5,     // volMult
            20       // rvolWindow
        );
    }

    @Test
    void testEmptyAndNullSeries() {
        List<SeriesPivot> empty = engine.detect(new double[]{}, params);
        assertTrue(empty.isEmpty(), "Empty series should return empty pivots");

        List<SeriesPivot> nullResult = engine.detect(null, params);
        assertTrue(nullResult.isEmpty(), "Null series should return empty pivots");
    }

    @Test
    void testMonotonicIncreasing() {
        // Series [1, 2, 3, ..., 100] has no reversals
        double[] series = new double[100];
        for (int i = 0; i < 100; i++) {
            series[i] = i + 1;
        }

        List<SeriesPivot> pivots = engine.detect(series, params);
        assertEquals(0, pivots.size(), "Monotonic increasing should produce 0 pivots");
    }

    @Test
    void testMonotonicDecreasing() {
        // Series [100, 99, 98, ..., 1] has no reversals
        double[] series = new double[100];
        for (int i = 0; i < 100; i++) {
            series[i] = 100 - i;
        }

        List<SeriesPivot> pivots = engine.detect(series, params);
        assertEquals(0, pivots.size(), "Monotonic decreasing should produce 0 pivots");
    }

    @Test
    void testSingleSineWave() {
        // Single sine period from 0 to 2π: [sin(0), sin(π/32), ..., sin(2π)]
        // Should produce 1 PEAK and 1 TROUGH
        int n = 65; // ~2π/0.1 points
        double[] series = new double[n];
        for (int i = 0; i < n; i++) {
            series[i] = Math.sin(2 * Math.PI * i / (n - 1));
        }

        List<SeriesPivot> pivots = engine.detect(series, params);
        // Expecting roughly 1 peak and 1 trough (exact count depends on threshold)
        assertTrue(pivots.size() >= 1, "Sine wave should produce at least 1 pivot");
        assertTrue(pivots.size() <= 3, "Sine wave should produce at most 3 pivots (peak, trough, endpoint artifact)");

        // Verify alternation between PEAK and TROUGH
        for (int i = 0; i < pivots.size() - 1; i++) {
            assertNotEquals(pivots.get(i).kind(), pivots.get(i + 1).kind(),
                "Consecutive pivots should alternate between PEAK and TROUGH");
        }
    }

    @Test
    void testTinyWiggleUnderNoiseFloor() {
        // Flat series with one tiny reversal that should NOT produce a pivot
        // Base: flat at 100, except indices 30-35 wiggle by 0.5 units
        double[] series = new double[100];
        for (int i = 0; i < 100; i++) {
            series[i] = 100.0;
        }
        series[32] = 100.1; // tiny up
        series[33] = 100.0; // tiny down

        List<SeriesPivot> pivots = engine.detect(series, params);
        assertEquals(0, pivots.size(), "Tiny wiggle (0.1 units on base 100) should not produce pivot");
    }

    @Test
    void testZeroCrossingNoNaN() {
        // Series that crosses zero: [10, 5, 0, -5, -10, -5, 0, 5, 10]
        // Should handle pctMin correctly and produce no NaN/infinity
        double[] series = { 10, 5, 0, -5, -10, -5, 0, 5, 10 };

        List<SeriesPivot> pivots = engine.detect(series, params);

        // Verify no NaN or infinite values in results
        for (SeriesPivot p : pivots) {
            assertTrue(Double.isFinite(p.value()), "Pivot value should be finite");
            assertTrue(Double.isFinite(p.significance()), "Pivot significance should be finite");
            assertTrue(Double.isFinite(p.atrAtPivot()), "Pivot atrAtPivot should be finite");
        }

        // Should detect at least the trough at -10
        boolean hasTrough = pivots.stream()
            .anyMatch(p -> p.kind() == PivotKind.TROUGH && p.value() == -10.0);
        assertTrue(hasTrough, "Should detect trough at -10");
    }

    @Test
    void testAllZeros() {
        // All zeros: [0, 0, 0, ..., 0]
        double[] series = new double[50];
        for (int i = 0; i < 50; i++) {
            series[i] = 0.0;
        }

        List<SeriesPivot> pivots = engine.detect(series, params);
        assertEquals(0, pivots.size(), "Flat series of zeros should produce 0 pivots");
    }

    @Test
    void testSingleSpikeInFlatSeries() {
        // 100 bars at 0, spike to 100 at index 50, back to 0
        double[] series = new double[101];
        for (int i = 0; i < 101; i++) {
            series[i] = 0.0;
        }
        series[50] = 100.0;

        List<SeriesPivot> pivots = engine.detect(series, params);

        // Should detect the spike as at least one pivot
        assertTrue(pivots.size() >= 1, "Single spike should produce at least 1 pivot");

        // Verify the spike value is captured
        boolean hasPeak = pivots.stream()
            .anyMatch(p -> p.kind() == PivotKind.PEAK && Math.abs(p.value() - 100.0) < 0.1);
        assertTrue(hasPeak, "Should detect peak near 100");
    }

    @Test
    void testSignificanceOrdering() {
        // Create two pivots with vastly different prominences
        // Up to 50, down to 10 (prominence=40), back up to 60 (prominence=50)
        double[] series = new double[50];
        for (int i = 0; i < 25; i++) {
            series[i] = i * 2;      // 0, 2, 4, ..., 48 (climb to 50)
        }
        for (int i = 25; i < 35; i++) {
            series[i] = 50 - (i - 25) * 4; // 50, 46, 42, ..., 10 (drop to 10)
        }
        for (int i = 35; i < 50; i++) {
            series[i] = 10 + (i - 35) * 2; // 10, 12, ..., 40 (climb back up)
        }

        List<SeriesPivot> pivots = engine.detect(series, params);

        // Should have at least 2 pivots
        assertTrue(pivots.size() >= 2, "Should produce at least 2 pivots");

        // Find peak and trough
        SeriesPivot peak = pivots.stream()
            .filter(p -> p.kind() == PivotKind.PEAK)
            .findFirst()
            .orElse(null);
        SeriesPivot trough = pivots.stream()
            .filter(p -> p.kind() == PivotKind.TROUGH)
            .findFirst()
            .orElse(null);

        assertNotNull(peak, "Should have at least one peak");
        assertNotNull(trough, "Should have at least one trough");

        // Trough (prominence from peak) should have lower significance than second peak
        if (pivots.size() >= 3) {
            SeriesPivot secondPeak = pivots.stream()
                .filter(p -> p.kind() == PivotKind.PEAK && p.idx() > peak.idx())
                .findFirst()
                .orElse(null);
            if (secondPeak != null) {
                assertTrue(trough.significance() <= secondPeak.significance(),
                    "Trough should have lower or equal significance than second peak (different prominences)");
            }
        }
    }

    @Test
    void testPivotIndexValidity() {
        double[] series = { 1, 10, 2, 8, 3 };

        List<SeriesPivot> pivots = engine.detect(series, params);

        // All pivot indices must be within bounds
        for (SeriesPivot p : pivots) {
            assertTrue(p.idx() >= 0 && p.idx() < series.length,
                "Pivot index " + p.idx() + " out of bounds for series of length " + series.length);
            // Value must match series at that index
            assertEquals(series[p.idx()], p.value(), 1e-10,
                "Pivot value must match series[idx]");
        }
    }

    @Test
    void testPivotsOrdered() {
        double[] series = new double[50];
        for (int i = 0; i < 50; i++) {
            series[i] = Math.sin(2 * Math.PI * i / 50);
        }

        List<SeriesPivot> pivots = engine.detect(series, params);

        // Pivots must be ordered by index
        for (int i = 0; i < pivots.size() - 1; i++) {
            assertTrue(pivots.get(i).idx() < pivots.get(i + 1).idx(),
                "Pivots must be ordered by index");
        }
    }

    @Test
    void testSignificanceInRange() {
        double[] series = new double[100];
        for (int i = 0; i < 100; i++) {
            series[i] = Math.sin(2 * Math.PI * i / 100);
        }

        List<SeriesPivot> pivots = engine.detect(series, params);

        // All significance values must be in [0, 1]
        for (SeriesPivot p : pivots) {
            assertTrue(p.significance() >= 0.0 && p.significance() <= 1.0,
                "Significance " + p.significance() + " out of range [0, 1]");
        }
    }

    @Test
    void testAtrAtPivotNonNegative() {
        double[] series = new double[50];
        for (int i = 0; i < 50; i++) {
            series[i] = 50 + Math.sin(2 * Math.PI * i / 50) * 20;
        }

        List<SeriesPivot> pivots = engine.detect(series, params);

        // All atrAtPivot must be non-negative
        for (SeriesPivot p : pivots) {
            assertTrue(p.atrAtPivot() >= 0.0, "atrAtPivot must be non-negative, got " + p.atrAtPivot());
        }
    }

    @Test
    void testDefaultParamsValid() {
        SignificanceParams defaults = SignificanceParams.ofDefaults();
        assertNotNull(defaults, "ofDefaults() should return non-null");
        assertEquals(14, defaults.atrLength());
        assertEquals(2.5, defaults.atrMult());
        assertEquals(0.02, defaults.pctMin());
        assertEquals(0.7, defaults.hysteresis());
        assertEquals(3, defaults.minBarsBetweenPivots());
        assertFalse(defaults.dynamicPctEnabled());
        assertEquals(1.5, defaults.volMult());
        assertEquals(20, defaults.rvolWindow());
    }
}
