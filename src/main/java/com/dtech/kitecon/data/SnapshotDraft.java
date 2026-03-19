package com.dtech.kitecon.data;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * SnapshotDraft - Stores snapshot drafts during pre-commit conversation phase
 * Allows users to refine their analysis before finalizing
 */
@Entity
@Table(name = "snapshot_draft", indexes = {
    @Index(name = "idx_username_status", columnList = "username,status"),
    @Index(name = "idx_symbol", columnList = "symbol"),
    @Index(name = "idx_created_at", columnList = "created_at")
})
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SnapshotDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Username of the draft creator
     */
    @Column(nullable = false)
    private String username;

    /**
     * Trading symbol
     */
    @Column(nullable = false, length = 100)
    private String symbol;

    /**
     * Timeframe
     */
    @Column(nullable = false, length = 20)
    private String timeframe;

    /**
     * Current chart state (updated as user refines)
     */
    @Lob
    @Column(name = "chart_state_json", columnDefinition = "LONGTEXT")
    private String chartStateJson;

    /**
     * Conversation history as JSON array
     * Format: [{"role": "user", "message": "...", "timestamp": "..."}, ...]
     */
    @Lob
    @Column(name = "conversation_history", columnDefinition = "LONGTEXT")
    private String conversationHistory;

    /**
     * Current pattern tags (comma-separated)
     */
    @Lob
    @Column(name = "pattern_tags", columnDefinition = "TEXT")
    private String patternTags;

    /**
     * Latest user comment/analysis
     */
    @Lob
    @Column(name = "user_comment", columnDefinition = "TEXT")
    private String userComment;

    /**
     * Latest AI feedback
     */
    @Lob
    @Column(name = "ai_feedback", columnDefinition = "TEXT")
    private String aiFeedback;

    /**
     * Draft status
     */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "DRAFT"; // DRAFT or COMMITTED

    /**
     * Intended visibility after commit
     */
    @Column(length = 20)
    @Builder.Default
    private String visibility = "PRIVATE"; // PRIVATE, PUBLIC, GROUP

    /**
     * Whether to perform AI validation
     */
    @Column(name = "perform_ai_validation")
    @Builder.Default
    private Boolean performAiValidation = true;

    /**
     * Timestamp when created
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * Timestamp when last modified
     */
    @Column(name = "last_modified_at", nullable = false)
    private LocalDateTime lastModifiedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (lastModifiedAt == null) {
            lastModifiedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        lastModifiedAt = LocalDateTime.now();
    }

    // ============ Helper Methods ============

    /**
     * Get pattern tags as a list
     */
    public List<String> getPatternTagsList() {
        if (patternTags == null || patternTags.trim().isEmpty()) {
            return List.of();
        }
        return Arrays.stream(patternTags.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    }

    /**
     * Set pattern tags from a list
     */
    public void setPatternTagsList(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            this.patternTags = null;
        } else {
            this.patternTags = String.join(",", tags);
        }
    }
}
