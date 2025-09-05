package com.dtech.chartpattern.api.dto;

import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagParams;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

public class ZigZagResponses {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StockResult {
        private String tradingSymbol;
        private String timeframe;
        private ZigZagParams params;
        private List<ZigZagPoint> pivots;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IndexResult {
        private String indexName;
        private List<StockResult> results;
    }
}
