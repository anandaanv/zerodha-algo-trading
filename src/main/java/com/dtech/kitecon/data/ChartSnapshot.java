package com.dtech.kitecon.data;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ChartSnapshot - Stores user's chart annotations with AI validation
 * Users can create snapshots of their chart analysis with pattern annotations
 */
@Entity
@Table(name = "chart_snapshot", indexes = {
    @Index(name = "idx_username", columnList = "username"),
    @Index(name = "idx_symbol", columnList = "symbol"),
    @Index(name = "idx_visibility", columnList = "visibility"),
    @Index(name = "idx_created_at", columnList = "created_at")
})
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChartSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Username of the snapshot creator
     */
    @Column(nullable = false)
    private String username;

    /**
     * Trading symbol (e.g., TCS, INFY)
     */
    @Column(nullable = false, length = 100)
    private String symbol;

    /**
     * Timeframe (e.g., 1h, 1d, 1w)
     */
    @Column(nullable = false, length = 20)
    private String timeframe;

    /**
     * Reference to the chart layout used (optional)
     */
    @Column(name = "layout_id")
    private Long layoutId;

    /**
     * Timestamp when the snapshot was taken
     */
    @Column(name = "snapshot_timestamp", nullable = false)
    private LocalDateTime snapshotTimestamp;

    /**
     * Start date of the chart range visible in snapshot
     */
    @Column(name = "start_date")
    private LocalDate startDate;

    /**
     * End date of the chart range visible in snapshot
     */
    @Column(name = "end_date")
    private LocalDate endDate;

    /**
     * Complete chart state including drawings, indicators, etc. as JSON
     * This allows recreating the exact chart view
     */
    @Lob
    @Column(name = "chart_state_json", columnDefinition = "TEXT")
    private String chartStateJson;

    /**
     * User's comment/analysis on the chart
     * e.g., "This stock is forming an Ending Diagonal Triangle"
     */
    @Lob
    @Column(name = "user_comment", columnDefinition = "TEXT")
    private String userComment;

    /**
     * Type of pattern identified (EDT, Wave3, Wave4, Triangle, etc.)
     * @deprecated Use patternTags instead for multi-tag support
     */
    @Column(name = "pattern_type", length = 100)
    private String patternType;

    /**
     * Multiple pattern tags (comma-separated)
     * e.g., "Head and Shoulders,Breakout,Resistance"
     */
    @Lob
    @Column(name = "pattern_tags", columnDefinition = "TEXT")
    private String patternTags;

    /**
     * AI validation result (valid, invalid, needs_clarification)
     */
    @Lob
    @Column(name = "ai_validation", columnDefinition = "TEXT")
    private String aiValidation;

    /**
     * AI's detailed analysis and reasoning
     */
    @Lob
    @Column(name = "ai_analysis", columnDefinition = "TEXT")
    private String aiAnalysis;

    /**
     * Internalized memory from pre-commit conversation
     * Summary of the user's thought process and refinements
     */
    @Lob
    @Column(name = "conversation_summary", columnDefinition = "TEXT")
    private String conversationSummary;

    /**
     * Reference to parent snapshot if this is a clone
     */
    @Column(name = "parent_snapshot_id")
    private Long parentSnapshotId;

    /**
     * Number of times this snapshot has been cloned
     */
    @Column(name = "clone_count")
    @Builder.Default
    private Integer cloneCount = 0;

    /**
     * Visibility level: private, public, or group
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SnapshotVisibility visibility = SnapshotVisibility.PRIVATE;

    /**
     * JSON array of group IDs if visibility is 'group'
     */
    @Column(name = "group_ids", columnDefinition = "JSON")
    private String groupIds;

    /**
     * Number of likes received
     */
    @Column(name = "likes_count")
    @Builder.Default
    private Integer likesCount = 0;

    /**
     * Number of views
     */
    @Column(name = "views_count")
    @Builder.Default
    private Integer viewsCount = 0;

    /**
     * Timestamp when created
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * Timestamp when last updated
     */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (snapshotTimestamp == null) {
            snapshotTimestamp = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ============ Helper Methods for Tags ============

    /**
     * Get pattern tags as a list
     */
    public java.util.List<String> getPatternTagsList() {
        if (patternTags == null || patternTags.trim().isEmpty()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(patternTags.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Set pattern tags from a list
     */
    public void setPatternTagsList(java.util.List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            this.patternTags = null;
        } else {
            this.patternTags = String.join(",", tags);
        }
    }

    public enum SnapshotVisibility {
        PRIVATE, PUBLIC, GROUP;

        @JsonCreator
        public static SnapshotVisibility fromString(String value) {
            if (value == null) {
                return null;
            }
            try {
                return SnapshotVisibility.valueOf(value.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                    "Invalid visibility value: " + value + ". Allowed values: private, public, group"
                );
            }
        }

        @JsonValue
        public String toJson() {
            return this.name().toLowerCase();
        }
    }
}
