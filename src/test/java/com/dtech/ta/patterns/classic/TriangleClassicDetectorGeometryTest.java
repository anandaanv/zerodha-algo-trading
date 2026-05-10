package com.dtech.ta.patterns.classic;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TriangleClassicDetectorGeometryTest {

    @ParameterizedTest
    @CsvSource({
        // Ascending: |A-C| <= avgBar AND |C-E| <= avgBar AND B < D < F < E
        "100.0, 90.0, 100.0, 95.0, 100.0, 98.0, 10.0, ASCENDING",
        "100.0, 85.0, 101.0, 87.0, 99.0, 92.0, 10.0, ASCENDING",
        "100.0, 80.0, 100.0, 82.0, 100.0, 85.0, 10.0, ASCENDING",
        "100.0, 90.0, 105.0, 95.0, 100.0, 98.0, 10.0, ASCENDING",

        // Descending: |B-D| <= avgBar AND A > C > E > F AND F >= D
        "100.0, 90.0, 98.0, 88.0, 96.0, 88.0, 10.0, DESCENDING",

        // Fail cases
        "100.0, 95.0, 100.0, 94.0, 100.0, 98.0, 10.0, NONE",
        "100.0, 90.0, 100.0, 95.0, 100.0, 101.0, 10.0, NONE",
    })
    void testClassify(double a, double b, double c, double d, double e, double f,
                      double avgBar, String expectedKind) {
        Optional<TrianglePattern.TriangleKind> result = TriangleClassicDetector.classify(
            a, b, c, d, e, f, avgBar
        );

        if ("NONE".equals(expectedKind)) {
            assertTrue(result.isEmpty(), "Expected no pattern for a=" + a);
        } else {
            assertTrue(result.isPresent(), "Expected pattern type for inputs a=" + a);
            assertEquals(expectedKind, result.get().name(), "Pattern type mismatch for a=" + a);
        }
    }
}
