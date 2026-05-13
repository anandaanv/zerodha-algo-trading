package com.dtech.ta.patterns.classic;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class ReverseHnsClassicDetectorGeometryTest {

    @ParameterizedTest
    @CsvSource({
        // Valid Reverse H&S: C < min(A,E), min(B,D) > max(A,E), F > E, |B-D| < avgBar, |C-E| > avgBar*0.6
        "85, 95, 75, 97, 87, 90, 10, true",
        "85, 96, 74, 98, 88, 91, 10, true",
        "90, 100, 80, 102, 92, 95, 10, true",

        // Inverse head not lowest (C >= min(A,E))
        "85, 95, 86, 97, 87, 90, 10, false",
        "85, 95, 85, 97, 85, 90, 10, false",

        // Neckline not above shoulders (min(B,D) <= max(A,E))
        "85, 85, 75, 97, 87, 90, 10, false",
        "85, 95, 75, 85, 87, 90, 10, false",

        // Close not above right shoulder (F <= E)
        "85, 95, 75, 97, 87, 87, 10, false",
        "85, 95, 75, 97, 87, 86, 10, false",

        // Neckline troughs too different (|B-D| >= avgBar)
        "85, 95, 75, 85, 87, 90, 10, false",
        "85, 85, 75, 97, 87, 90, 10, false",

        // Inverse head not meaningfully lower (|C-E| <= avgBar*0.6)
        "85, 95, 80, 97, 81, 90, 10, false",
        "85, 95, 81, 97, 82, 90, 10, false",

        // All conditions met but avgBar = 0
        "85, 95, 75, 97, 87, 90, 0, false",

        // Edge case: shoulder height difference exactly on threshold
        "85, 95, 79, 97, 87, 90, 10, true",

        // Edge case: |B-D| just under avgBar
        "85, 96, 75, 97, 87, 90, 10, true",
    })
    void testReverseHns(double a, double b, double c, double d, double e, double f,
                        double avgBar, boolean expected) {
        boolean result = ReverseHnsClassicDetector.test(a, b, c, d, e, f, avgBar);
        assertEquals(expected, result, "Reverse HNS test failed for a=" + a);
    }
}
