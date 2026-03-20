package com.dtech.kitecon.service.model;

import lombok.Data;
import java.util.List;

@Data
public class PromptBuildRequest {
    private List<ConversationMessage> history;
    private String userMessage;

    @Data
    public static class ConversationMessage {
        private String role;    // "user" | "assistant"
        private String content;
    }
}
