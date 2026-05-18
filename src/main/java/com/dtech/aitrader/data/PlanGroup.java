package com.dtech.aitrader.data;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * v2 plan_group — a single market structure that may resolve into multiple mutually-exclusive
 * branches. One row per scan-time hypothesis on a symbol+timeframe.
 *
 * Companion to the memsys "trade memory" (one memsys memory per plan_group, linked via
 * {@link #memsysMemoryId}). Postgres is source of truth for operational state; memsys is source
 * of truth for content and threaded discussion.
 *
 * See "AI Trader v2 — Plan Groups & Branches Schema [v1.1]" memsys memory for the design rationale,
 * lifecycle rules, and trigger cascade semantics.
 */
@Entity
@Table(
    name = "plan_group",
    indexes = {
        @Index(name = "ix_plan_group_user_symbol_state", columnList = "user_id, symbol, state"),
        @Index(name = "ix_plan_group_state_valid_until", columnList = "state, valid_until")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 32)
    private String symbol;

    /** Timeframe at scan time — "1D", "1W", "1H", etc. */
    @Column(nullable = false, length = 16)
    private String timeframe;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PlanGroupState state;

    /** Free-form Agent 1 prose: what's the structural reading? */
    @Column(name = "underlying_hypothesis", columnDefinition = "TEXT")
    private String underlyingHypothesis;

    /** Free-form Agent 1 prose: how was the structure validated against pivots/labels/playbook? */
    @Column(name = "structural_validation", columnDefinition = "TEXT")
    private String structuralValidation;

    /** JSON array of drawing ids that contributed to this plan_group, serialized as text. */
    @Column(name = "source_drawing_ids", columnDefinition = "TEXT")
    private String sourceDrawingIdsJson;

    /** JSON array of pivot label ids referenced by Agent 1, serialized as text. */
    @Column(name = "source_label_ids", columnDefinition = "TEXT")
    private String sourceLabelIdsJson;

    /** JSON array of symbol_journal_note ids referenced by Agent 1, serialized as text. */
    @Column(name = "source_journal_note_ids", columnDefinition = "TEXT")
    private String sourceJournalNoteIdsJson;

    /** JSON array of playbook rule ids (memsys memory uuids) the agent cited, serialized as text. */
    @Column(name = "playbook_rules_applied", columnDefinition = "TEXT")
    private String playbookRulesAppliedJson;

    /** Shared decision zone — the price band where the hypothesis resolves one way or the other. */
    @Column(name = "decision_zone_low", precision = 12, scale = 2)
    private BigDecimal decisionZoneLow;

    @Column(name = "decision_zone_high", precision = 12, scale = 2)
    private BigDecimal decisionZoneHigh;

    @Column(name = "decision_zone_rationale", columnDefinition = "TEXT")
    private String decisionZoneRationale;

    @Column(name = "valid_until")
    private LocalDateTime validUntil;

    /** Self-referential nullable FK — points at the older plan_group this one replaced. */
    @Column(name = "supersedes_group_id")
    private Long supersedesGroupId;

    @Column(name = "scan_summary", columnDefinition = "TEXT")
    private String scanSummary;

    /** Prompt/agent version that produced this group (e.g. "v1.1"). */
    @Column(name = "agent_version", length = 16)
    private String agentVersion;

    /** Verbatim Agent 1 output JSON for audit/replay. */
    @Column(name = "raw_agent_output", columnDefinition = "MEDIUMTEXT")
    private String rawAgentOutput;

    /** memsys companion memory uuid (set after orchestrator writes the trade memory). */
    @Column(name = "memsys_memory_id", length = 64)
    private String memsysMemoryId;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (state == null) state = PlanGroupState.WATCHING;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
