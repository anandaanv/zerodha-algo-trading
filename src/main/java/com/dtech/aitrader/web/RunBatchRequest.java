package com.dtech.aitrader.web;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RunBatchRequest {
    private List<String> symbols;
    private String tabId;
    private Long layoutId;
    private String timeframe;
}
