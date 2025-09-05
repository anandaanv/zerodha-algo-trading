package com.dtech.chartpattern.api.dto;

import lombok.Data;

import java.util.List;

public class ZigZagRequests {

    @Data
    public static class StockRequest {
        private String tradingSymbol;
        private List<String> timeframes; // names of Interval enum
        private boolean persist = false;
    }

    @Data
    public static class IndexRequest {
        private String indexName;
        private List<String> timeframes;
        private boolean persist = false;
    }
}
