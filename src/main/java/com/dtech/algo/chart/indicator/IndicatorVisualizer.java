package com.dtech.algo.chart.indicator;

import com.dtech.algo.series.IntervalBarSeries;
import org.jfree.chart.plot.XYPlot;
import org.ta4j.core.indicators.CachedIndicator;

import java.util.Map;

/**
 * A common interface for all indicator visualizers.
 * This interface defines methods for creating plots for indicators and adding indicators to existing plots.
 */
public interface IndicatorVisualizer {
    /**
     * Create a plot for the indicator
     * @param indicator The indicator to visualize
     * @param barSeries The associated bar series
     * @return An XYPlot configured for the indicator
     */
    XYPlot createIndicatorPlot(CachedIndicator<?> indicator, IntervalBarSeries barSeries);

    /**
     * Add the indicator to an existing plot (for overlay indicators)
     * @param plot The plot to add the indicator to
     * @param indicator The indicator to add
     * @param barSeries The associated bar series
     */
    void addIndicatorToPlot(XYPlot plot, CachedIndicator<?> indicator, IntervalBarSeries barSeries);

    /**
     * Generate metadata about the indicator for AI interpretation
     * @param indicator The indicator
     * @return Map of metadata attributes
     */
    Map<String, Object> generateIndicatorMetadata(CachedIndicator<?> indicator);
}