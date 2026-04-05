package com.dtech.wavelab.elliott.dto;

import lombok.Data;

@Data
public class TriangleAnalyzeRequest {
    private String symbol;
    private String timeframe;
    private Integer candleLimit;
    private String proposerA;
    private String proposerB;
    private String evaluator;
}
