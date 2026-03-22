package com.dtech.kitecon.data;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ChartLayout - Stores TradingView chart configurations (indicators, settings, etc.)
 * These are symbol-agnostic and can be reused across different symbols
 */
@Entity
@Table(name = "chart_layout")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChartLayout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * User-defined name for the layout (e.g., "My Trading Setup", "Scalping Layout")
     */
    @Column(nullable = false)
    private String name;

    /**
     * Username of the owner (from User.username)
     * Used to filter layouts per user
     */
    @Column(nullable = false)
    private String username;

    /**
     * TradingView chart configuration stored as JSON
     * Contains: indicators, chart settings, overlays (NOT drawings)
     */
    @Lob
    @Column(name = "layout_content", columnDefinition = "TEXT")
    private String layoutContent;

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

    /**
     * Co-Pilot: investigation validity window in minutes (default 60).
     * A new investigation for this layout will expire after this many minutes.
     */
    @Column(name = "copilot_validity_minutes")
    @Builder.Default
    private Integer copilotValidityMinutes = 60;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
