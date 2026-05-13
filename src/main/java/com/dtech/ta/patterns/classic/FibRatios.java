package com.dtech.ta.patterns.classic;

/**
 * Fibonacci ratio utilities: canonical series and snap/range functions.
 * Reference: docs/spec-classic-patterns-port.md (fib_ser definition)
 */
public class FibRatios {

    /**
     * Canonical Fibonacci series used for harmonic pattern detection.
     */
    public static final double[] FIB_SERIES = {0.236, 0.382, 0.5, 0.618, 0.707, 0.786, 0.886, 1.0};

    /**
     * Snap a ratio to the nearest value in FIB_SERIES.
     *
     * @param ratio the ratio to snap
     * @return the closest value in FIB_SERIES
     */
    public static double snapToFib(double ratio) {
        double minDistance = Double.MAX_VALUE;
        double closestFib = FIB_SERIES[0];

        for (double fib : FIB_SERIES) {
            double distance = Math.abs(ratio - fib);
            if (distance < minDistance) {
                minDistance = distance;
                closestFib = fib;
            }
        }

        return closestFib;
    }

    /**
     * Check if a ratio falls within an inclusive range.
     *
     * @param ratio the ratio to check
     * @param minInclusive minimum value (inclusive)
     * @param maxInclusive maximum value (inclusive)
     * @return true if minInclusive <= ratio <= maxInclusive
     */
    public static boolean isInRange(double ratio, double minInclusive, double maxInclusive) {
        return ratio >= minInclusive && ratio <= maxInclusive;
    }
}
