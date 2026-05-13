package com.dtech.ta.patterns.classic;

/**
 * Immutable representation of a trendline.
 * Represents a line in price-vs-bar-index space with slope and y-intercept.
 * Reference: docs/spec-classic-patterns-port.md (Trendline section)
 */
public record Line(
    double slope,
    double yIntercept,
    int startBarIndex,
    double startPrice,
    int endBarIndex,
    double endPrice
) {

    /**
     * Compute the price value on the line at a given bar index.
     * Formula: y = m*x + b, where m is slope, b is yIntercept, x is barIndex.
     */
    public double valueAt(int barIndex) {
        return slope * barIndex + yIntercept;
    }
}
