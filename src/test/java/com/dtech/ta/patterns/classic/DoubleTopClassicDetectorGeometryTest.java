package com.dtech.ta.patterns.classic;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class DoubleTopClassicDetectorGeometryTest {

    @ParameterizedTest
    @CsvSource({
        // Valid Double Top: C-B < ATR*4, |A-C| <= avgBar*0.5, cVol < aVol, B < min(A,C), B < D < C
        "100.0, 90.0, 100.0, 95.0, 5000.0, 4000.0, 10.0, 3.0, true",
        "100.0, 90.0, 100.0, 94.0, 5000.0, 4500.0, 10.0, 3.0, true",

        // Peaks too far apart
        "100.0, 90.0, 106.0, 95.0, 5000.0, 4000.0, 10.0, 3.0, false",

        // Second peak has higher volume
        "100.0, 90.0, 100.0, 95.0, 4000.0, 5000.0, 10.0, 3.0, false",

        // B not below both peaks
        "100.0, 90.0, 90.0, 95.0, 5000.0, 4000.0, 10.0, 3.0, false",

        // D not between B and C
        "100.0, 90.0, 100.0, 89.0, 5000.0, 4000.0, 10.0, 3.0, false",
        "100.0, 90.0, 100.0, 100.0, 5000.0, 4000.0, 10.0, 3.0, false",
    })
    void testDoubleTop(double a, double b, double c, double d, double aVol, double cVol,
                       double avgBar, double atr, boolean expected) {
        boolean result = DoubleTopClassicDetector.test(a, b, c, d, aVol, cVol, avgBar, atr);
        assertEquals(expected, result, "Double Top test failed for a=" + a);
    }
}
