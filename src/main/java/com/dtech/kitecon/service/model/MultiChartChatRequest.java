package com.dtech.kitecon.service.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data @Builder @AllArgsConstructor @NoArgsConstructor
public class MultiChartChatRequest {

    private List<ChartContext> charts;
    private String userMessage;
    private String mode;   // "REASON" or "VALIDATE"

    @Data @Builder @AllArgsConstructor @NoArgsConstructor
    public static class ChartContext {
        private String label;          // editable tab name
        private String symbol;
        private String timeframe;      // ui key
        private String chartStateJson;
        private Long visibleFrom;      // epoch seconds, nullable
        private Long visibleTo;        // epoch seconds, nullable
    }
}
