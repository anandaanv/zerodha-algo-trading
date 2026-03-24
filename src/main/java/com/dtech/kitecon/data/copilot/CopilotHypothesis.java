package com.dtech.kitecon.data.copilot;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A named, tracked interpretation of current price structure for a symbol.
 * Multiple hypotheses can exist for a single symbol simultaneously.
 * Never cleared when a trade is entered.
 *
 * State machine: WATCHING → BUILDING → CONFIRMED → TRADE_ACTIVE → INVALIDATED | EXPIRED
 */
@Entity
@Table(name = "copilot_hypothesis")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CopilotHypothesis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "investigation_id", nullable = false)
    private Long investigationId;

    @Column(name = "symbol", nullable = false, length = 50)
    private String symbol;

    /** Short label, e.g. "Wave 4 Triangle" */
    @Column(nullable = false, length = 500)
    private String label;

    @Lob
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** wave_1 | wave_2 | wave_3 | wave_4 | wave_5 | wave_a | wave_b | wave_c */
    @Lob
    @Column(name = "wave_context", columnDefinition = "MEDIUMTEXT")
    private String waveContext;

    /** triangle | double_bottom | double_top | flat_abc | zigzag | flag | wedge | complex_wxyz */
    @Column(name = "pattern", length = 100)
    private String pattern;

    /** Current stage within the pattern, e.g. "approaching_E", "leg_C_forming" */
    @Column(name = "stage", length = 100)
    private String stage;

    /**
     * WATCHING — pattern detected, monitoring for confirmation
     * BUILDING — confirmation conditions partially met
     * CONFIRMED — all conditions met, awaiting expert entry decision
     * TRADE_ACTIVE — expert has entered, linked trade on dashboard
     * INVALIDATED — invalidation condition triggered
     * EXPIRED — conditions never materialized
     */
    @Column(name = "state", nullable = false, length = 30)
    @Builder.Default
    private String state = "WATCHING";

    /** JSON: Map<String, String> layer → "pass" | "fail" | "pending" | "warning" */
    @Lob
    @Column(name = "confidence_layers", columnDefinition = "TEXT")
    private String confidenceLayers;

    /** JSON: {entry_zone, sl, tp, conditions_needed[], trigger_description} */
    @Lob
    @Column(name = "anticipatory_trade", columnDefinition = "TEXT")
    private String anticipatoryTrade;

    /** JSON: {entry_zone, sl, tp, conditions_needed[], trigger_description} */
    @Lob
    @Column(name = "confirmation_trade", columnDefinition = "TEXT")
    private String confirmationTrade;

    /** JSON: List<String> — conditions that kill this hypothesis */
    @Lob
    @Column(name = "invalidation_conditions", columnDefinition = "TEXT")
    private String invalidationConditions;

    /** Reason for invalidation when state = INVALIDATED */
    @Lob
    @Column(name = "invalidation_reason", columnDefinition = "TEXT")
    private String invalidationReason;

    /** SYSTEM_SUGGESTED | EXPERT_SUGGESTED */
    @Column(name = "origin", length = 30)
    @Builder.Default
    private String origin = "SYSTEM_SUGGESTED";

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
