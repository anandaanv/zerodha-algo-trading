package com.dtech.drawingscan.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DrawingScanResponse {
    private String symbol;
    private String scanTf;
    private Range range;
    private List<DrawingResult> drawings;
    private Summary summary;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Range {
        private Long from;
        private Long to;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DrawingResult {
        private String id;
        private String type;
        private double score;
        private Map<String, Object> metrics;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Summary {
        private int totalDrawings;
        private String bestId;
        private double bestScore;
    }
}
