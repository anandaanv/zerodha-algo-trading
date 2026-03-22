package com.dtech.kitecon.data.copilot;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A system-detected anomaly that requires explicit expert acknowledgment.
 * System never silently proceeds past an unacknowledged flag.
 *
 * Levels: INFO | WARNING | CRITICAL
 * Actions: ACCEPT | OVERRIDE | DISMISS
 */
@Entity
@Table(name = "copilot_anomaly_flag")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CopilotAnomalyFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "investigation_id", nullable = false)
    private Long investigationId;

    /** Nullable — flag may not be tied to a specific hypothesis */
    @Column(name = "hypothesis_id")
    private Long hypothesisId;

    @Lob
    @Column(name = "flag_text", columnDefinition = "TEXT", nullable = false)
    private String flagText;

    /** INFO | WARNING | CRITICAL */
    @Column(name = "level", nullable = false, length = 20)
    @Builder.Default
    private String level = "WARNING";

    /** Which skill raised this flag, e.g. "triangle", "wave_4" */
    @Column(name = "raised_by_skill", length = 100)
    private String raisedBySkill;

    @Column(name = "acknowledged", nullable = false)
    @Builder.Default
    private Boolean acknowledged = false;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    /** ACCEPT | OVERRIDE | DISMISS */
    @Column(name = "action_taken", length = 20)
    private String actionTaken;

    @Lob
    @Column(name = "expert_notes", columnDefinition = "TEXT")
    private String expertNotes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
