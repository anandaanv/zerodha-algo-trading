package com.dtech.kitecon.service.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PromptBuildResponse {
    private String message;
    private String extractedPrompt;
}
