package com.dtech.algo.chart.renderer;

import com.dtech.algo.chart.config.ChartConfig;
import org.jfree.chart.JFreeChart;
import org.jfree.data.xy.XYDataset;

/**
 * Interface for chart renderers that create JFreeChart objects from datasets.
 * Each chart type (candlestick, line, etc.) will have its own renderer implementation.
 */
public interface ChartRenderer {
    /**
     * Renders a chart from the provided dataset and configuration
     * 
     * @param dataset The dataset containing the chart data
     * @param config The chart configuration
     * @return A fully configured JFreeChart object
     */
    JFreeChart renderChart(XYDataset dataset, ChartConfig config);
}