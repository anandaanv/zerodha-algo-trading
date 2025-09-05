package com.dtech.algo.chart.config;

import com.dtech.algo.series.IntervalBarSeries;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import org.ta4j.core.indicators.CachedIndicator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A data class that encapsulates all configuration options for a single chart.
 * This class uses Lombok annotations to reduce boilerplate code.
 */
@Data
@Builder
@AllArgsConstructor
public class ChartConfig {
    private List<IntervalBarSeries> barSeries;    // Price data for multiple intervals
    private List<CachedIndicator<?>> indicators;  // Technical indicators to display
    private ChartType chartType;                  // Candle, line, bar, etc.
    private int displayedBars;                    // Number of bars to display
    private String title;                         // Chart title
    private boolean showVolume;                   // Whether to show volume
    private boolean showLegend;                   // Whether to show legend
    private Map<String, String> additionalOptions; // Extra customization options

    /**
     * Default constructor with sensible defaults
     */
    public ChartConfig() {
        this.barSeries = new ArrayList<>();
        this.indicators = new ArrayList<>();
        this.chartType = ChartType.CANDLESTICK;
        this.displayedBars = 0; // 0 means all available bars
        this.title = "Chart";
        this.showVolume = false;
        this.showLegend = true;
        this.additionalOptions = new HashMap<>();
    }

    /**
     * Validates that the configuration has all required fields set
     * @return true if the configuration is valid
     */
    public boolean isValid() {
        return barSeries != null && !barSeries.isEmpty() && chartType != null;
    }

    /**
     * Gets the number of bars to display
     * @return The number of bars to display, or the total number of bars if displayedBars is 0
     */
    public int getEffectiveDisplayedBars() {
        if (displayedBars <= 0 && barSeries != null && !barSeries.isEmpty()) {
            // Use the first bar series for backward compatibility
            return barSeries.get(0).getBarCount();
        }
        return displayedBars;
    }
    
    /**
     * Adds a bar series to the list
     * @param series The bar series to add
     */
    public void addBarSeries(IntervalBarSeries series) {
        if (this.barSeries == null) {
            this.barSeries = new ArrayList<>();
        }
        this.barSeries.add(series);
    }
    
    /**
     * For backward compatibility - sets a single bar series
     * @param series The bar series to set
     */
    public void setBarSeries(IntervalBarSeries series) {
        if (this.barSeries == null) {
            this.barSeries = new ArrayList<>();
        } else {
            this.barSeries.clear();
        }
        this.barSeries.add(series);
    }

    /**
     * Gets an additional option value
     * @param key The option key
     * @param defaultValue The default value if the option is not set
     * @return The option value or the default value
     */
    public String getOption(String key, String defaultValue) {
        if (additionalOptions == null || !additionalOptions.containsKey(key)) {
            return defaultValue;
        }
        return additionalOptions.get(key);
    }
}