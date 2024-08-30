package com.dtech.ta.patterns;

import com.dtech.ta.TrendLineCalculated;

public class TrianglePattern {
    private final TrendLineCalculated highTrendline;
    private final TrendLineCalculated lowTrendline;

    public TrianglePattern(TrendLineCalculated highTrendline, TrendLineCalculated lowTrendline) {
        this.highTrendline = highTrendline;
        this.lowTrendline = lowTrendline;
    }

    public TrendLineCalculated getHighTrendline() {
        return highTrendline;
    }

    public TrendLineCalculated getLowTrendline() {
        return lowTrendline;
    }

    public String toString() {
        return lowTrendline.toString();
    }
}
