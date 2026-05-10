package com.dtech.ta.patterns.classic;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for pure geometry checks in bearish VCP detection.
 * Each test calls BearishVcpDetector.test() with fixed scalar inputs.
 * Reference: docs/spec-vcp-pattern-port.md (Unit tests section)
 */
class BearishVcpDetectorGeometryTest {

    @ParameterizedTest(name = "{index}: {0}")
    @CsvSource({
        "Textbook bearish VCP,100.0,110.0,101.0,106.0,103.0,1.0,true",
        "A and C too far apart (>avgBar),100.0,110.0,95.0,106.0,103.0,1.0,false",
        "B and D too close (no contraction),100.0,110.0,101.0,110.5,103.0,1.0,false",
        "E already broke down below C,100.0,110.0,101.0,106.0,98.0,1.0,false",
        "D above B (no contraction),100.0,110.0,101.0,112.0,103.0,1.0,false",
        "C significantly < A,100.0,110.0,95.0,106.0,103.0,1.0,false",
        "avgBarLength = 0,100.0,110.0,100.0,106.0,103.0,0.0,false",
        "All-equal degenerate,100.0,100.0,100.0,100.0,100.0,1.0,false",
        "Tight range valid,100.0,105.0,100.5,103.5,102.0,1.0,true",
        "Tight contraction valid,100.0,120.0,101.0,112.0,102.0,5.0,true"
    })
    void testBearishVcpGeometry(String description, double a, double b, double c, double d,
                                double e, double avgBarLength, boolean expected) {
        boolean result = BearishVcpDetector.test(a, b, c, d, e, avgBarLength);
        assertEquals(expected, result, "Test case: " + description);
    }
}
