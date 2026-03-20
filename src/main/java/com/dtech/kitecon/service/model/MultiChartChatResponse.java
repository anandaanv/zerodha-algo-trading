package com.dtech.kitecon.service.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @Builder @AllArgsConstructor @NoArgsConstructor
public class MultiChartChatResponse {
    private String message;
    private int chartCount;
    private int totalDrawingCount;
    private String mode;
}
