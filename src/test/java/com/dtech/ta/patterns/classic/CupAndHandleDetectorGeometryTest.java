package com.dtech.ta.patterns.classic;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Parameterized geometry tests for Cup and Handle pattern validation.
 * Tests the static test() method with various geometric configurations.
 */
class CupAndHandleDetectorGeometryTest {

    /**
     * Test Cup and Handle geometry with various scenarios.
     * Format: a, b, c, d, aBar, bBar, cBar, dBar, avgBarLength, expectedResult
     *
     * Geometric rules being tested:
     *   - Rims equal: |A - C| <= avgBarLength
     *   - Cup deep: (A - B) > avgBarLength * 2
     *   - Cup broad: both bToATime >= 5 and cToATime >= 5
     *   - Handle shallow: |C - D| < (A - B) * 0.5
     */
    @ParameterizedTest(name = "{index}: a={0} b={1} c={2} d={3} bars={4},{5},{6},{7} avgBar={8} => {9}")
    @CsvSource({
        // Clean cup and handle: all conditions met
        "100, 60, 98, 85, 0, 5, 10, 15, 10, true",

        // Rims too unequal: |A - C| > avgBarLength
        "100, 60, 85, 75, 0, 5, 10, 15, 10, false",

        // Cup too shallow: (A - B) <= avgBarLength * 2
        "100, 85, 98, 90, 0, 5, 10, 15, 10, false",

        // Cup too narrow: B-to-A time < 5 bars
        "100, 60, 98, 85, 0, 2, 10, 15, 10, false",

        // Cup too narrow: C-to-A time < 5 bars
        "100, 60, 98, 85, 0, 5, 3, 15, 10, false",

        // Handle too deep: |C - D| >= (A - B) * 0.5
        "100, 60, 98, 60, 0, 5, 10, 15, 10, false",

        // Valid cup but rim slightly off center (still within avgBar tolerance)
        "100, 60, 101, 85, 0, 5, 10, 15, 10, true",

        // Edge case: handle at exactly 50% cup depth (should be false, <50% required)
        "100, 60, 98, 78, 0, 5, 10, 15, 10, false",

        // Edge case: handle at 49% cup depth (should be true)
        "100, 60, 98, 79, 0, 5, 10, 15, 10, true",

        // Valid with different avgBarLength
        "100, 50, 99, 75, 0, 6, 12, 18, 15, true"
    })
    void testCupAndHandleGeometry(double a, double b, double c, double d,
                                   int aBar, int bBar, int cBar, int dBar,
                                   double avgBarLength, boolean expectedResult) {
        boolean result = CupAndHandleDetector.test(a, b, c, d, aBar, bBar, cBar, dBar, avgBarLength);
        assertEquals(expectedResult, result,
            String.format("Cup geometry: a=%f b=%f c=%f d=%f, bars=[%d,%d,%d,%d], avgBar=%f",
                a, b, c, d, aBar, bBar, cBar, dBar, avgBarLength));
    }
}
