package com.dtech.kitecon.data.copilot;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A single observation from Phase 1 (SCAN).
 * Records what a skill detected (or didn't detect) on the chart.
 * Observations are promoted to hypotheses during Phase 2 (REASON).
 */
@Entity
@Table(name = "copilot_observation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CopilotObservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "investigation_id", nullable = false)
    private Long investigationId;

    @Column(name = "skill_key", nullable = false, length = 100)
    private String skillKey;

    @Column(name = "pattern_detected", nullable = false)
    @Builder.Default
    private Boolean patternDetected = false;

    /** What was detected: "triangle", "wave_4", "double_bottom" */
    @Column(name = "pattern_type", length = 100)
    private String patternType;

    /** HIGH | MEDIUM | LOW */
    @Column(name = "confidence", length = 20)
    private String confidence;

    @Lob
    @Column(name = "structural_details", columnDefinition = "TEXT")
    private String structuralDetails;

    @Column(name = "stage", length = 100)
    private String stage;

    /** JSON: List<KeyLevel> */
    @Lob
    @Column(name = "key_levels", columnDefinition = "TEXT")
    private String keyLevels;

    /** JSON: List<DrawingPoint> — coordinates for TradingView pattern drawing */
    @Lob
    @Column(name = "drawing_points", columnDefinition = "TEXT")
    private String drawingPoints;

    /** TradingView tool: triangle_pattern, elliott_impulse_wave, etc. */
    @Column(name = "drawing_type", length = 100)
    private String drawingType;

    @Column(name = "timeframe", length = 20)
    private String timeframe;

    /** JSON: List<String> — contradictions found */
    @Lob
    @Column(name = "contradictions", columnDefinition = "TEXT")
    private String contradictions;

    @Lob
    @Column(name = "reasoning", columnDefinition = "TEXT")
    private String reasoning;

    /** Full raw AI response for debugging */
    @Lob
    @Column(name = "raw_response", columnDefinition = "TEXT")
    private String rawResponse;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
