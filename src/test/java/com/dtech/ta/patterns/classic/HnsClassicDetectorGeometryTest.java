package com.dtech.ta.patterns.classic;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class HnsClassicDetectorGeometryTest {

    @ParameterizedTest
    @CsvSource({
        // Valid H&S: C > max(A,E), max(B,D) < min(A,E), F < E, |B-D| < avgBar, |C-E| > avgBar*0.6
        "95, 85, 105, 83, 93, 90, 10, true",
        "95, 84, 106, 82, 94, 91, 10, true",
        "90, 80, 100, 78, 88, 86, 10, true",

        // Head not highest (C <= max(A,E))
        "95, 85, 94, 83, 95, 90, 10, false",
        "95, 85, 95, 83, 95, 90, 10, false",

        // Neckline not below shoulders (max(B,D) >= min(A,E))
        "95, 85, 105, 95, 93, 90, 10, false",
        "95, 95, 105, 83, 93, 90, 10, false",

        // Close not below right shoulder (F >= E)
        "95, 85, 105, 83, 93, 93, 10, false",
        "95, 85, 105, 83, 93, 94, 10, false",

        // Neckline troughs too different (|B-D| >= avgBar)
        "95, 85, 105, 95, 93, 90, 10, false",
        "95, 75, 105, 85, 93, 90, 10, false",

        // Head not meaningfully higher (|C-E| <= avgBar*0.6)
        "95, 85, 100, 83, 99, 90, 10, false",
        "95, 85, 99, 83, 98, 90, 10, false",

        // All conditions met but avgBar = 0
        "95, 85, 105, 83, 93, 90, 0, false",

        // Edge case: shoulder height difference exactly on threshold (avgBar*0.6)
        "95, 85, 101, 83, 93, 90, 10, true",

        // Edge case: |B-D| just under avgBar
        "95, 84, 105, 83, 93, 90, 10, true",
    })
    void testHns(double a, double b, double c, double d, double e, double f,
                 double avgBar, boolean expected) {
        boolean result = HnsClassicDetector.test(a, b, c, d, e, f, avgBar);
        assertEquals(expected, result, "HNS test failed for a=" + a);
    }
}
