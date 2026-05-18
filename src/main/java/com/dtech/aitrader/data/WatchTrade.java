package com.dtech.aitrader.data;

import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "watch_trade")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WatchTrade {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String symbol;

    @Column(name = "source_type", nullable = false, length = 32)
    private String sourceType;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    @Column(name = "generated_for_date", nullable = false)
    private LocalDate generatedForDate;

    @Column(length = 8)
    private String direction;

    @Column(precision = 12, scale = 2)
    private BigDecimal entry;

    @Column(precision = 12, scale = 2)
    private BigDecimal sl;

    @Column(precision = 12, scale = 2)
    private BigDecimal target;

    private Double rr;

    private Double confidence;

    @Column(name = "trigger_type", length = 32)
    private String triggerType;

    @Column(name = "trigger_spec_json", columnDefinition = "TEXT")
    private String triggerSpecJson;

    @Column(columnDefinition = "TEXT")
    private String rationale;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "triggered_at")
    private LocalDateTime triggeredAt;

    @Column(name = "triggered_price", precision = 12, scale = 2)
    private BigDecimal triggeredPrice;

    @Column(name = "validity_until")
    private LocalDateTime validityUntil;

    @Column(name = "agent_decision_id")
    private Long agentDecisionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // ── v2 branch fields ─────────────────────────────────────────────
    // Nullable so existing watch_trade rows (and any new ones still using the v1
    // flat-list path) continue to function. A row becomes a "v2 branch" when
    // plan_group_id is set — the orchestrator dual-writes plan_group + branches
    // atomically at scan time.

    /** FK to plan_group.id when this row is one branch of a multi-hypothesis structure. */
    @Column(name = "plan_group_id")
    private Long planGroupId;

    /** Short label given by the agent to identify the branch (e.g. "long-breakout", "short-truncation"). */
    @Column(name = "branch_label", length = 64)
    private String branchLabel;

    /** JSON array (text-serialized) of sibling watch_trade ids that should die when this one triggers. */
    @Column(name = "sibling_kill_branch_ids", columnDefinition = "TEXT")
    private String siblingKillBranchIdsJson;

    /** Mirrored from the parent plan_group for fast indexable range queries by the trigger. */
    @Column(name = "decision_zone_low", precision = 12, scale = 2)
    private BigDecimal decisionZoneLow;

    @Column(name = "decision_zone_high", precision = 12, scale = 2)
    private BigDecimal decisionZoneHigh;
}
