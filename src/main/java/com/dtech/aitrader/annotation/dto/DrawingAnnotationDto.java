package com.dtech.aitrader.annotation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DrawingAnnotationDto {
    private Long id;
    private String tabUuid;
    private String symbol;
    private String interval;
    private String drawingId;
    private String intent;
    private String intentParamsJson;
    private String geometryJson;
    private String note;
    private Integer weight;
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;
}
