package com.dtech.algo.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for TradingView chart generation
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradingViewChartResponse {

    /**
     * The symbol for which charts were generated
     */
    private String symbol;

    /**
     * URL of the generated chart image
     */
    private String chartUrl;

    /**
     * Base64 encoded image data (optional)
     */
    private String base64Image;

    /**
     * Information about each individual chart in the panel
     */
    private List<ChartPanelInfo> panels;

    /**
     * Error message, if any
     */
    private String errorMessage;

    /**
     * Information about an individual chart panel
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChartPanelInfo {
        /**
         * The timeframe of this chart
         */
        private String timeframe;

        /**
         * Number of candles in this chart
         */
        private int candleCount;

        /**
         * Position in the grid (row, column)
         */
        private String position;
    }
}
