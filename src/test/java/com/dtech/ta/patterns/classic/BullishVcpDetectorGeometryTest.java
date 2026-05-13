package com.dtech.ta.patterns.classic;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for pure geometry checks in bullish VCP detection.
 * Each test calls BullishVcpDetector.test() with fixed scalar inputs.
 * Reference: docs/spec-vcp-pattern-port.md (Unit tests section)
 */
class BullishVcpDetectorGeometryTest {

    @ParameterizedTest(name = "{index}: {0}")
    @CsvSource({
        "Textbook bullish VCP,100,90,99,94,97,1.0,true",
        "A and C too far apart (>avgBar),100,90,95,94,97,1.0,false",
        "B and D too close (no contraction),100,90,99,90.5,97,1.0,false",
        "E already broke out above C,100,90,99,94,102,1.0,false",
        "D below B (no contraction),100,90,99,88,97,1.0,false",
        "C significantly > A,100,90,105,94,97,1.0,false",
        "avgBarLength = 0,100,90,100,94,97,0.0,false",
        "All-equal degenerate,100,100,100,100,100,1.0,false",
        "Tight range valid,100,95,99.5,96.5,98,1.0,true",
        "Tight contraction valid,100,80,99,88,98,5.0,true"
    })
    void testBullishVcpGeometry(String description, double a, double b, double c, double d,
                                double e, double avgBarLength, boolean expected) {
        boolean result = BullishVcpDetector.test(a, b, c, d, e, avgBarLength);
        assertEquals(expected, result, "Test case: " + description);
    }
}
