package com.dtech.ta.trendline;

import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.ta.BarTuple;
import com.dtech.ta.TrendLineCalculated;
import org.ta4j.core.BarSeries;
import java.util.List;

public interface TrendlineAnalyser {
    List<TrendLineCalculated> analyze(IntervalBarSeries series, boolean validateActive);

    // Combine high/lows using convex hull and sliding window
    List<BarTuple> getCombinedHighLows(BarSeries series);
}
