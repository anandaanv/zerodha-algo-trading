package com.dtech.aitrader.annotation.dto;

import lombok.Data;

@Data
public class SaveThesisRequest {
    private String tabUuid;
    private String symbol;
    private String bias;
    private String regime;
    private Integer horizonDays;
    private String thesisText;
}
