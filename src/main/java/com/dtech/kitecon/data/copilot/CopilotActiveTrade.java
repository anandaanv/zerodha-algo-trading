package com.dtech.kitecon.data.copilot;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A confirmed hypothesis that has been entered as a live trade.
 * Always linked to a parent hypothesis. The hypothesis is never deleted when trade is created.
 *
 * Two classification dimensions (per spec Section 11.3):
 * 1. Origin: SYSTEM_SUGGESTED + expert approved | EXPERT_SUGGESTED + system recognised
 * 2. Stage: ANTICIPATORY | ACTIVE | CLOSED
 * Special flag: is_override_trade (expert entered against system objection)
 */
@Entity
@Table(name = "copilot_active_trade")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CopilotActiveTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hypothesis_id", nullable = false)
    private Long hypothesisId;

    @Column(name = "investigation_id", nullable = false)
    private Long investigationId;

    @Column(name = "symbol", nullable = false, length = 50)
    private String symbol;

    /** ANTICIPATORY | CONFIRMATION */
    @Column(name = "entry_type", nullable = false, length = 30)
    private String entryType;

    @Column(name = "entry_price", precision = 15, scale = 4)
    private BigDecimal entryPrice;

    @Column(name = "sl", precision = 15, scale = 4)
    private BigDecimal sl;

    @Column(name = "tp", precision = 15, scale = 4)
    private BigDecimal tp;

    @Column(name = "size", precision = 15, scale = 4)
    private BigDecimal size;

    /** ENTERED | MONITORING | CLOSED */
    @Column(name = "state", nullable = false, length = 20)
    @Builder.Default
    private String state = "ENTERED";

    /** SYSTEM_SUGGESTED | EXPERT_SUGGESTED */
    @Column(name = "origin", length = 30)
    @Builder.Default
    private String origin = "SYSTEM_SUGGESTED";

    /** True when expert entered against system objection */
    @Column(name = "is_override_trade", nullable = false)
    @Builder.Default
    private Boolean isOverrideTrade = false;

    /** What the system objected to (populated when is_override_trade = true) */
    @Lob
    @Column(name = "system_objection", columnDefinition = "TEXT")
    private String systemObjection;

    /** Expert's stated reasoning for the override */
    @Lob
    @Column(name = "override_reason", columnDefinition = "TEXT")
    private String overrideReason;

    @Column(name = "close_price", precision = 15, scale = 4)
    private BigDecimal closePrice;

    /** WIN | LOSS | BREAKEVEN | OPEN */
    @Column(name = "outcome", length = 20)
    private String outcome;

    @Lob
    @Column(name = "close_notes", columnDefinition = "TEXT")
    private String closeNotes;

    /** For override trades: how far price moved in the direction the system predicted */
    @Column(name = "system_prediction_accuracy", length = 100)
    private String systemPredictionAccuracy;

    /** Whether the system's concern ultimately materialized */
    @Column(name = "system_concern_materialized")
    private Boolean systemConcernMaterialized;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

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
