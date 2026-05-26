package com.dtech.aitrader.data;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Walk-forward outcome for a single {@link RuleFiring}. Computed post-hoc by the
 * {@code WalkForwardOutcomeScorer} once enough bars have elapsed past the firing's as-of.
 *
 * <p>HIT / MISS / INVALIDATED / PENDING + MFE/MAE + bars-to-target give the eval layer enough
 * dimensions to ask any edge question without re-running the backtest.
 *
 * <p>One row per firing per scoring run. Re-running the scorer with a different
 * {@link #windowBars} value supersedes the previous row (single-row constraint via the firing-id
 * PK).
 */
@Entity
@Table(name = "firing_outcome")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FiringOutcome {

    /** PK + FK to {@link RuleFiring#getId()}. One outcome per firing. Length 64 = SHA-256 hex. */
    @Id
    @Column(name = "firing_id", length = 64)
    private String firingId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Outcome outcome;

    /** Bar date the outcome resolved on (null when {@link #outcome} = PENDING). */
    @Column(name = "outcome_bar_date")
    private LocalDate outcomeBarDate;

    /** Maximum favourable excursion as % from trigger across the window (always ≥ 0). */
    @Column(name = "mfe_pct", nullable = false)
    private double mfePct;

    /** Maximum adverse excursion as % from trigger across the window (always ≥ 0). */
    @Column(name = "mae_pct", nullable = false)
    private double maePct;

    @Column(name = "bars_to_target")
    private Integer barsToTarget;

    @Column(name = "bars_to_invalidation")
    private Integer barsToInvalidation;

    @Column(name = "window_bars", nullable = false)
    private int windowBars;

    @Column(name = "scored_at", nullable = false)
    private LocalDateTime scoredAt;

    @PrePersist
    @PreUpdate
    void onWrite() {
        scoredAt = LocalDateTime.now();
    }

    public enum Outcome { HIT, MISS, INVALIDATED, PENDING }
}
