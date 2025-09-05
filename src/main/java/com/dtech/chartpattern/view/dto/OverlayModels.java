package com.dtech.chartpattern.view.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight overlay models that can be serialized to JSON
 * and consumed by a TradingView HTML renderer.
 */
public class OverlayModels {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ZigZagPointView {
        private long time;      // epoch seconds
        private double value;   // price
        private String type;    // "HIGH" | "LOW"
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrendlineSegment {
        private long fromTime;  // epoch seconds
        private long toTime;    // epoch seconds
        private double y1;      // price at fromTime
        private double y2;      // price at toTime
        private String side;    // "support" | "resistance"
        private String state;   // "ACTIVE" | "RETESTING" | "BROKEN"
    }
}
