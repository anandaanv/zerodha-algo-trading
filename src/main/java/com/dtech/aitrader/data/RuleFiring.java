package com.dtech.aitrader.data;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row per deterministic rule firing. Append-only. Same table now serves BOTH the original
 * pilot (single-pass, VERDICT-shaped firings) AND the multi-pass engine (Pass 0-6 firings of
 * many shapes — FACT / CANDIDATE / ELIMINATION / CLASSIFICATION / CONFIRMATION / VERDICT).
 *
 * <p>Multi-pass columns added per SPEC-004 ({@code 4e185036}):
 * <ul>
 *   <li>{@link #family} — rule family (EW / PATTERN / INDICATOR / STRUCTURE / SYNTHESIS)</li>
 *   <li>{@link #passNum} — which of the 7 passes emitted this firing (0..6)</li>
 *   <li>{@link #firesOn} — what kind of firing this is</li>
 *   <li>{@link #refsJson} — array of prior firing IDs this firing acts upon (e.g. a Pass-3
 *       ELIMINATION refs the Pass-2 CANDIDATE it kills)</li>
 *   <li>{@link #priorDeltaJson} — the discriminated-union {@code PriorDelta} payload, present on
 *       firings that adjust a candidate's prior (ELIMINATION / CLASSIFICATION / CONFIRMATION)</li>
 *   <li>{@link #basePrior} — initial prior for CANDIDATE firings (Pass-2 enumerations)</li>
 *   <li>{@link #roundNum} — feedback round, 1 for the initial pass; ≥2 for spawn-from-feedback</li>
 *   <li>{@link #payloadJson} — rule-specific blob (geometry details, magnitudes, etc.)</li>
 * </ul>
 *
 * <p>Backward-compat with the pilot: original columns ({@code bias}, {@code trigger_price},
 * {@code invalidation_price}, {@code final_conviction}, {@code context_signature}) remain NOT-NULL
 * at the DB level. For intermediate (non-VERDICT) firings, callers populate sentinels:
 * {@link Bias#NEUTRAL}, {@code 0.0} for prices/conviction, and {@code "(intermediate)"} for
 * {@code context_signature}. Only VERDICT firings (Pass 6 synthesis) carry the actionable values.
 *
 * <p>Outcomes ({@link FiringOutcome}) are scored ONLY for VERDICT firings (Q7 — see convergence
 * memo {@code 9c60e777}). Intermediate firings are explanation / audit / replay; they are NEVER
 * outcome-scored.
 */
@Entity
@Table(
    name = "rule_firing",
    indexes = {
        @Index(name = "ix_rule_firing_rule_symbol_asof", columnList = "rule_id, symbol, as_of"),
        @Index(name = "ix_rule_firing_signature", columnList = "context_signature"),
        @Index(name = "ix_rule_firing_symbol_tf_asof", columnList = "symbol, tf, as_of"),
        @Index(name = "ix_rule_firing_pass_fires_on", columnList = "pass_num, fires_on")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleFiring {

    /**
     * Content-addressable id — SHA-256 digest of the firing's logical identity (O1 ratification
     * {@code ada56b20}). 64 hex chars; length=64 accommodates the full digest (was 36 for UUID).
     */
    @Id
    @Column(length = 64)
    private String id;

    /** Deterministic rule identifier, e.g. {@code "DOUBLE_BOTTOM"}. */
    @Column(name = "rule_id", nullable = false, length = 64)
    private String ruleId;

    @Column(nullable = false, length = 32)
    private String symbol;

    /** Timeframe label (pilot: only {@code "DAILY"}). */
    @Column(nullable = false, length = 16)
    private String tf;

    /** Bar date when this firing was emitted; honours the SymbolContext as-of cutoff. */
    @Column(name = "as_of", nullable = false)
    private LocalDate asOf;

    /**
     * Directional bias the rule assigned. Nullable now (Q4 ratification {@code 7885ad63}) — only
     * VERDICT firings carry a meaningful bias; intermediate firings store {@code NULL}.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private Bias bias;

    /** Nullable — only VERDICT firings carry trigger prices. */
    @Column(name = "trigger_price")
    private Double triggerPrice;

    /** Nullable — only VERDICT firings carry invalidation prices. */
    @Column(name = "invalidation_price")
    private Double invalidationPrice;

    @Column(name = "target_price")
    private Double targetPrice;

    /** Composite conviction in {@code [0,1]}. Nullable — populated for VERDICT/CANDIDATE only. */
    @Column(name = "final_conviction")
    private Double finalConviction;

    /** Per-component scores ({@code {"geometry":0.7,"sr":0.9,"confluence":0.8,...}}) for audit. */
    @Column(name = "conviction_components_json", columnDefinition = "TEXT")
    private String convictionComponentsJson;

    /** Full ContextProbeResult payload as JSON — macroRegime / srPosition / indicatorConfluence. */
    @Column(name = "context_json", columnDefinition = "TEXT")
    private String contextJson;

    /**
     * Controlled-vocab tag the eval SQL groups by. Nullable for intermediate firings (Q4
     * ratification); VERDICT firings always populate it.
     */
    @Column(name = "context_signature", length = 128)
    private String contextSignature;

    /** Pivot indices + bar refs that triggered the firing — enables replay / debugging. */
    @Column(name = "evidence_json", columnDefinition = "TEXT")
    private String evidenceJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // ── Multi-pass engine columns (SPEC-004) — nullable for pilot-era backward compat ────────────

    /** Rule family: EW / PATTERN / INDICATOR / STRUCTURE / SYNTHESIS. */
    @Column(name = "family", length = 16)
    private String family;

    /** Pass that emitted this firing (0..6 per {@link com.dtech.aitrader.v2.rules.Pass#order}). */
    @Column(name = "pass_num")
    private Integer passNum;

    /** FACT / CANDIDATE / ELIMINATION / CLASSIFICATION / CONFIRMATION / VERDICT. */
    @Column(name = "fires_on", length = 16)
    private String firesOn;

    /** JSON array of prior firing IDs this firing depends on / acts upon. */
    @Column(name = "refs_json", columnDefinition = "TEXT")
    private String refsJson;

    /** Discriminated-union PriorDelta JSON; present on firings that adjust a candidate's prior. */
    @Column(name = "prior_delta_json", columnDefinition = "TEXT")
    private String priorDeltaJson;

    /** Initial prior for CANDIDATE firings (Pass-2 enumeration output). */
    @Column(name = "base_prior")
    private Double basePrior;

    /** Feedback round number — 1 for initial passes; ≥2 for spawn-from-contradiction work. */
    @Column(name = "round_num")
    private Integer roundNum;

    /** Rule-specific payload as JSON (geometry, magnitudes, ratios — whatever the rule needs). */
    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    /**
     * SHA-256 of the firing's logical identity ({@code rule_id|symbol|as_of|sorted(refs)|payload})
     * — backed by a unique index so backtest re-runs are idempotent (Q1, ratification
     * {@code 7885ad63}). Nullable for pilot-era rows that predate this column; the migration
     * runner adds the unique index but MySQL allows multiple NULL rows under a unique index.
     */
    @Column(name = "firing_digest", length = 64)
    private String firingDigest;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (roundNum == null) roundNum = 1;
    }

    public enum Bias { LONG, SHORT, NEUTRAL }
}
