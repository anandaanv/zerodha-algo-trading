package com.dtech.algo.chart.renderer;

import com.dtech.algo.chart.config.ChartType;
import org.springframework.stereotype.Component;

/**
 * Factory class for creating chart renderers based on chart type.
 * Uses the strategy pattern to select the appropriate renderer implementation.
 */
@Component
public class ChartRendererFactory {
    
    /**
     * Gets the appropriate renderer for the specified chart type
     * 
     * @param chartType The type of chart to render
     * @return A ChartRenderer implementation for the specified chart type
     * @throws IllegalArgumentException if the chart type is not supported
     */
    public ChartRenderer getRenderer(ChartType chartType) {
        if (chartType == ChartType.CANDLESTICK) {
            return new CandlestickChartRenderer();
        } else if (chartType == ChartType.LINE) {
            return new LineChartRenderer();
        }
        return new CandlestickChartRenderer();
    }
}