package com.dtech.aitrader.web;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiLevelsRequest {
    private String symbol;
    private String tabId;
    private Long layoutId;
    private String timeframe;
}
