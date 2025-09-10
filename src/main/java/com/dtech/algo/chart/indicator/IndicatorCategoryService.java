package com.dtech.algo.chart.indicator;

import org.springframework.stereotype.Component;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

import java.util.HashMap;
import java.util.Map;

/**
 * Service to determine the appropriate visualization category for each indicator.
 * This service categorizes indicators as either overlay or separate panel based on their type.
 */
@Component
public class IndicatorCategoryService {
    
    // Map to cache indicator class to visualizer class mappings
    private final Map<Class<?>, Class<? extends IndicatorVisualizer>> visualizerMap = new HashMap<>();
    
    /**
     * Constructor initializes the visualizer mappings
     */
    public IndicatorCategoryService() {
        // This would be populated with actual visualizer implementations
        // For now, we'll just define the categories
    }
    
    /**
     * Categorize an indicator as overlay or separate panel
     * @param indicator The indicator to categorize
     * @return IndicatorCategory (OVERLAY or SEPARATE_PANEL)
     */
    public IndicatorCategory categorizeIndicator(CachedIndicator<?> indicator) {
        // Moving Averages are overlay indicators
        if (indicator instanceof SMAIndicator || 
            indicator instanceof EMAIndicator) {
            return IndicatorCategory.OVERLAY;
        }
        
        // Bollinger Bands are overlay indicators
        if (indicator instanceof BollingerBandsUpperIndicator ||
            indicator instanceof BollingerBandsMiddleIndicator ||
            indicator instanceof BollingerBandsLowerIndicator) {
            return IndicatorCategory.OVERLAY;
        }
        
        // Standard Deviation is typically an overlay
        if (indicator instanceof StandardDeviationIndicator) {
            return IndicatorCategory.OVERLAY;
        }
        
        // MACD is a separate panel indicator
        if (indicator instanceof MACDIndicator) {
            return IndicatorCategory.SEPARATE_PANEL;
        }
        
        // RSI is a separate panel indicator
        if (indicator instanceof RSIIndicator) {
            return IndicatorCategory.SEPARATE_PANEL;
        }
        
        // Volume is a separate panel indicator
        if (indicator instanceof VolumeIndicator) {
            return IndicatorCategory.SEPARATE_PANEL;
        }
        
        // Default to separate panel for unknown indicators
        return IndicatorCategory.SEPARATE_PANEL;
    }
    
    /**
     * Get the appropriate visualizer for a separate panel indicator
     * @param indicator The indicator
     * @return The appropriate visualizer class
     */
    public Class<? extends IndicatorVisualizer> getVisualizerForIndicator(CachedIndicator<?> indicator) {
        // This would return the appropriate visualizer class for the indicator
        // For now, we'll return null as we haven't implemented the visualizers yet
        return null;
    }
    
    /**
     * Check if an indicator is a volume indicator
     * @param indicator The indicator to check
     * @return true if the indicator is a volume indicator
     */
    public boolean isVolumeIndicator(CachedIndicator<?> indicator) {
        return indicator instanceof VolumeIndicator;
    }
}