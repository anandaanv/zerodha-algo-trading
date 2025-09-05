package com.dtech.algo.controller.dto;

import com.dtech.algo.series.Interval;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Map;

/**
 * Request DTO for TradingView chart generation
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradingViewChartRequest {

    /**
     * The trading symbol for which to generate charts
     */
    @NotBlank(message = "Symbol is required")
    private String symbol;

    /**
     * List of timeframes to include in the chart
     */
    @NotEmpty(message = "At least one timeframe is required")
    private List<Interval> timeframes;

    /**
     * Number of candles to include in each chart
     */
    @Positive(message = "Candle count must be positive")
    @Builder.Default
    private int candleCount = 100;

    /**
     * Chart layout configuration (grid size, e.g. 2x2)
     */
    @Builder.Default
    private String layout = "2x2";

    /**
     * Optional chart title
     */
    private String title;

    /**
     * Whether to show volume indicator
     */
    @Builder.Default
    private boolean showVolume = true;

    /**
     * Optional overlays by timeframe (key = timeframe name, e.g., "Day", "Week").
     * Each entry contains support/resistance levels and optional trendlines to draw on the chart.
     */
    private Map<String, OverlayLevels> overlays;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OverlayLevels {
        private List<Double> support;
        private List<Double> resistance;
        private List<TrendLine> trendlines; // optional
        // Optional label to help styling (e.g., "weekly", "daily")
        private String label;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrendLine {
        // Epoch seconds and price for start and end points
        private Long startTs;
        private Double startPrice;
        private Long endTs;
        private Double endPrice;
        // Optional styling hints
        private String label;
        private String color;
    }
}
