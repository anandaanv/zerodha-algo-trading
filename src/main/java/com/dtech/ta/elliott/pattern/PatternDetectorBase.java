package com.dtech.ta.elliott.pattern;

import org.ta4j.core.BarSeries;

public abstract class PatternDetectorBase {
    protected BarSeries series;

    public PatternDetectorBase(BarSeries series) {
        this.series = series;
    }

    public abstract boolean detectPattern();
}
