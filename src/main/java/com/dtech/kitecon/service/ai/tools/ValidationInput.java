package com.dtech.kitecon.service.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * ValidationInput - Input data for tool validation
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ValidationInput {

    /**
     * Symbol being analyzed
     */
    private String symbol;

    /**
     * Timeframe of the chart
     */
    private String timeframe;

    /**
     * Pattern type to validate
     */
    private PatternType patternType;

    /**
     * Extracted drawings from chart
     */
    private List<Drawing> drawings;

    /**
     * OHLC price data for the visible range
     */
    private List<OHLCData> priceData;

    /**
     * User's comment/description
     */
    private String userComment;

    /**
     * Raw chart state JSON (for advanced parsing)
     */
    private JsonNode chartStateJson;

    /**
     * Additional metadata
     */
    private JsonNode metadata;

    /**
     * Drawing - Represents a user-drawn element on the chart
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Drawing {
        private String type; // trendline, horizontal_line, triangle, fibonacci, etc.
        private List<Point> points;
        private DrawingProperties properties;
    }

    /**
     * Point - A coordinate on the chart (time + price)
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Point {
        private long timestamp; // Unix timestamp in seconds
        private double price;
    }

    /**
     * DrawingProperties - Visual properties of a drawing
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DrawingProperties {
        private String color;
        private Integer lineWidth;
        private String lineStyle; // solid, dashed, dotted
        private String label;
    }

    /**
     * OHLCData - Candlestick data
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class OHLCData {
        private long timestamp;
        private double open;
        private double high;
        private double low;
        private double close;
        private Long volume;
    }
}
