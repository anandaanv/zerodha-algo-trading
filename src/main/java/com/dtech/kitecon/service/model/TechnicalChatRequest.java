package com.dtech.kitecon.service.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TechnicalChatRequest {
    private String symbol;
    private String timeframe;       // ui key: "1h", "1d", etc.
    private String chartStateJson;  // full state from getChartState()
    private String userMessage;     // null in REASON mode
    private String mode;            // "REASON" or "VALIDATE"
}
