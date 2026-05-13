package com.dtech.ta.patterns.classic;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class DoubleBottomClassicDetectorGeometryTest {

    @ParameterizedTest
    @CsvSource({
        // Valid Double Bottom: B-C < ATR*4, |A-C| <= avgBar*0.5, cVol < aVol, B > max(A,C), B > D > C
        "80.0, 90.0, 80.0, 85.0, 5000.0, 4000.0, 10.0, 3.0, true",
        "80.0, 90.0, 80.0, 86.0, 5000.0, 4500.0, 10.0, 3.0, true",

        // Valleys too far apart
        "80.0, 90.0, 74.0, 85.0, 5000.0, 4000.0, 10.0, 3.0, false",

        // Second valley has higher volume
        "80.0, 90.0, 80.0, 85.0, 4000.0, 5000.0, 10.0, 3.0, false",

        // B not above both valleys
        "80.0, 90.0, 90.0, 85.0, 5000.0, 4000.0, 10.0, 3.0, false",

        // D not between C and B
        "80.0, 90.0, 80.0, 79.0, 5000.0, 4000.0, 10.0, 3.0, false",
        "80.0, 90.0, 80.0, 90.0, 5000.0, 4000.0, 10.0, 3.0, false",
    })
    void testDoubleBottom(double a, double b, double c, double d, double aVol, double cVol,
                          double avgBar, double atr, boolean expected) {
        boolean result = DoubleBottomClassicDetector.test(a, b, c, d, aVol, cVol, avgBar, atr);
        assertEquals(expected, result, "Double Bottom test failed for a=" + a);
    }
}
