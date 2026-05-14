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
public class SymbolThesisDto {
    private Long id;
    private String tabUuid;
    private String symbol;
    private String bias;
    private String regime;
    private Integer horizonDays;
    private String thesisText;
    private Instant updatedAt;
}
