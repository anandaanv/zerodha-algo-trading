package com.dtech.kitecon.service.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TechnicalChatResponse {
    private String message;       // AI prose (may be a question asking user to draw)
    private int drawingCount;     // how many drawings were found
    private String mode;
}
