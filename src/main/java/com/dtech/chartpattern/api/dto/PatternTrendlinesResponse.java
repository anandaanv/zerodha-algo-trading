package com.dtech.chartpattern.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatternTrendlinesResponse {
    private String tradingSymbol;
    private String timeframe;
    private List<Line> lines;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        private String groupId;
        private String patternType;
        private String side;
        private int startIdx;
        private int endIdx;
        private LocalDateTime startTs;
        private LocalDateTime endTs;
        private double y1;
        private double y2;
        private double slopePerBar;
        private double intercept;
        private double confidence;
    }
}
