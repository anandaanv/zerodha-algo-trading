package com.dtech.kitecon.data.copilot;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * User-editable orchestrator instructions.
 * One row per user — stores the static instruction portion of the orchestrator prompt.
 * Falls back to the hardcoded default when no row exists.
 */
@Entity
@Table(name = "copilot_orchestrator_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CopilotOrchestratorConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /** The editable instruction text. Replaces the hardcoded static instructions. */
    @Lob
    @Column(name = "instructions", columnDefinition = "TEXT", nullable = false)
    private String instructions;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
