package com.dtech.drawingscan.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DrawingScanRequest {
    private String symbol;
    private String drawingsTf;
    private String scanTf;
    private Long fromSec;
    private Long toSec;
}
