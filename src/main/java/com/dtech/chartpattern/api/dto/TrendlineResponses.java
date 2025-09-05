package com.dtech.chartpattern.api.dto;

import com.dtech.chartpattern.persistence.TrendlineRecord;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

public class TrendlineResponses {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StockResult {
        private String tradingSymbol;
        private String timeframe;
        private List<TrendlineRecord> trendlines;
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
