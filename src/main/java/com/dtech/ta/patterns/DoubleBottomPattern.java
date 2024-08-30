package com.dtech.ta.patterns;

import com.dtech.ta.TrendLineCalculated;

// Define the DoubleBottomPattern class to hold the pattern details
public class DoubleBottomPattern {
    private final int firstLowIndex;
    private final int secondLowIndex;
    private final int retraceHighIndex;
    private final TrendLineCalculated trendline;

    public DoubleBottomPattern(int firstLowIndex, int secondLowIndex, int retraceHighIndex, TrendLineCalculated trendline) {
        this.firstLowIndex = firstLowIndex;
        this.secondLowIndex = secondLowIndex;
        this.retraceHighIndex = retraceHighIndex;
        this.trendline = trendline;
    }

    public int getFirstLowIndex() {
        return firstLowIndex;
    }

    public int getSecondLowIndex() {
        return secondLowIndex;
    }

    public int getRetraceHighIndex() {
        return retraceHighIndex;
    }

    public TrendLineCalculated getTrendline() {
        return trendline;
    }
}

