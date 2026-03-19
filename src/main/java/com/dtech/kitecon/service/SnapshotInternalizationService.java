package com.dtech.kitecon.service;

import com.dtech.kitecon.data.SnapshotDraft;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * SnapshotInternalizationService - Converts conversation history to memory/summary
 * This service takes the iterative refinement conversation and creates a concise summary
 * that captures the essence of the user's analytical journey
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SnapshotInternalizationService {

    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;

    private ChatClient chatClient;

    /**
     * Internalize (summarize) a conversation into memory
     * Takes the full conversation history and creates a concise summary
     */
    public String internalizeConversation(List<ConversationMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }

        try {
            if (chatClient == null) {
                chatClient = chatClientBuilder.build();
            }

            String prompt = buildInternalizationPrompt(messages);

            String summary = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

            log.info("Internalized conversation: {} messages -> {} chars summary",
                messages.size(), summary.length());

            return summary.trim();

        } catch (Exception e) {
            log.error("Error internalizing conversation", e);
            // Fallback: create simple summary from messages
            return createFallbackSummary(messages);
        }
    }

    /**
     * Internalize from SnapshotDraft
     */
    public String internalizeDraft(SnapshotDraft draft) {
        try {
            if (draft.getConversationHistory() == null || draft.getConversationHistory().trim().isEmpty()) {
                return null;
            }

            // Parse conversation history JSON
            List<ConversationMessage> messages = objectMapper.readValue(
                draft.getConversationHistory(),
                new TypeReference<List<ConversationMessage>>() {}
            );

            return internalizeConversation(messages);

        } catch (Exception e) {
            log.error("Error internalizing draft conversation", e);
            return null;
        }
    }

    /**
     * Build AI prompt for internalization
     */
    private String buildInternalizationPrompt(List<ConversationMessage> messages) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("You are a senior technical analyst reviewing a trader's analytical journey.\n");
        prompt.append("The trader refined their chart analysis through multiple iterations.\n");
        prompt.append("Create a concise summary that captures:\n\n");

        prompt.append("1. MAIN PATTERN(S) IDENTIFIED: What patterns did they ultimately identify?\n");
        prompt.append("2. KEY LEVELS: Important support/resistance, entry/exit points, targets\n");
        prompt.append("3. CONVICTION: How confident are they? Did their confidence evolve?\n");
        prompt.append("4. RISK FACTORS: What concerns or risks did they mention?\n");
        prompt.append("5. REFINEMENTS: How did their analysis improve through the conversation?\n\n");

        prompt.append("Keep the summary under 300 words. Be concise but capture the analytical evolution.\n\n");
        prompt.append("=== CONVERSATION HISTORY ===\n\n");

        for (int i = 0; i < messages.size(); i++) {
            ConversationMessage msg = messages.get(i);
            prompt.append(String.format("[%d] %s: %s\n\n",
                i + 1,
                msg.getRole().toUpperCase(),
                msg.getMessage()));
        }

        prompt.append("=== END CONVERSATION ===\n\n");
        prompt.append("Now provide the internalized summary in this format:\n\n");
        prompt.append("**Pattern(s):** [brief]\n");
        prompt.append("**Key Levels:** [brief]\n");
        prompt.append("**Conviction:** [brief]\n");
        prompt.append("**Risks:** [brief]\n");
        prompt.append("**Evolution:** [brief - how analysis improved]\n");

        return prompt.toString();
    }

    /**
     * Create fallback summary without AI (if API fails)
     */
    private String createFallbackSummary(List<ConversationMessage> messages) {
        StringBuilder summary = new StringBuilder();
        summary.append("**Analysis Summary**\n\n");

        List<ConversationMessage> userMessages = messages.stream()
            .filter(m -> "user".equalsIgnoreCase(m.getRole()))
            .collect(Collectors.toList());

        if (!userMessages.isEmpty()) {
            summary.append("User performed ").append(userMessages.size())
                   .append(" refinement(s) to their analysis.\n\n");

            // Include first and last user message
            summary.append("**Initial thought:**\n")
                   .append(truncate(userMessages.get(0).getMessage(), 150))
                   .append("\n\n");

            if (userMessages.size() > 1) {
                summary.append("**Final analysis:**\n")
                       .append(truncate(userMessages.get(userMessages.size() - 1).getMessage(), 150))
                       .append("\n");
            }
        }

        return summary.toString();
    }

    /**
     * Truncate text to max length
     */
    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 3) + "...";
    }

    /**
     * Add a message to conversation history (for building conversation during draft phase)
     */
    public String addMessageToHistory(String existingHistory, ConversationMessage newMessage) {
        try {
            List<ConversationMessage> messages;

            if (existingHistory == null || existingHistory.trim().isEmpty()) {
                messages = new ArrayList<>();
            } else {
                messages = objectMapper.readValue(
                    existingHistory,
                    new TypeReference<List<ConversationMessage>>() {}
                );
            }

            messages.add(newMessage);

            return objectMapper.writeValueAsString(messages);

        } catch (Exception e) {
            log.error("Error adding message to history", e);
            // Return as new conversation
            try {
                return objectMapper.writeValueAsString(List.of(newMessage));
            } catch (Exception e2) {
                log.error("Failed to serialize new message", e2);
                return "[]";
            }
        }
    }

    // ============ DTOs ============

    @Data
    public static class ConversationMessage {
        private String role; // "user" or "assistant"
        private String message;
        private String timestamp; // ISO timestamp
        private MessageMetadata metadata;

        public static ConversationMessage user(String message) {
            ConversationMessage msg = new ConversationMessage();
            msg.setRole("user");
            msg.setMessage(message);
            msg.setTimestamp(java.time.LocalDateTime.now().toString());
            return msg;
        }

        public static ConversationMessage assistant(String message) {
            ConversationMessage msg = new ConversationMessage();
            msg.setRole("assistant");
            msg.setMessage(message);
            msg.setTimestamp(java.time.LocalDateTime.now().toString());
            return msg;
        }
    }

    @Data
    public static class MessageMetadata {
        private String actionType; // "validation", "suggestion", "clarification"
        private Double confidence;
        private List<String> tags;
    }
}
