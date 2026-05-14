package com.dtech.aitrader.annotation.dto;

import lombok.Data;

@Data
public class SaveAnnotationRequest {
    private String tabUuid;
    private String symbol;
    private String interval;
    private String drawingId;
    private String intent;
    private String intentParamsJson;
    private String geometryJson;
    private String note;
    private Integer weight;
}
