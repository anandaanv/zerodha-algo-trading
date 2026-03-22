package com.dtech.kitecon.data.copilot;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A modular skill — a self-contained knowledge unit for one specific trading domain.
 * Skills are the AI reasoning layer: all trading knowledge lives here, never in application code.
 *
 * Each skill has 7 standard components (per spec Section 8.2).
 */
@Entity
@Table(name = "copilot_skill")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CopilotSkill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Display name, e.g. "Triangle Skill", "Wave 4 Skill" */
    @Column(nullable = false, length = 200)
    private String name;

    /** Short description of what this skill covers */
    @Column(length = 500)
    private String description;

    /**
     * Category: WAVE | PATTERN | CONFIRMATION | OVERRIDE
     * WAVE: wave-position skills (Wave 1–5, A, B, C)
     * PATTERN: chart pattern skills (Triangle, Double Bottom, etc.)
     * CONFIRMATION: indicator/trigger skills (RSI, MACD, Candlestick, Fibonacci)
     * OVERRIDE: context-invalidation skills (Volume Anomaly, Gap, Liquidity Event)
     */
    @Column(nullable = false, length = 50)
    private String category;

    /** Sub-category, e.g. "wave_4", "triangle", "rsi" */
    @Column(name = "skill_key", length = 100)
    private String skillKey;

    // ─── Seven standard skill components ──────────────────────────────────────

    /** Component 1: How to recognise this pattern/wave/signal. Minimum requirements. */
    @Lob
    @Column(name = "identification_rules", columnDefinition = "TEXT")
    private String identificationRules;

    /** Component 2: Current stage within the pattern. How to determine it from price data. */
    @Lob
    @Column(name = "stage_detection", columnDefinition = "TEXT")
    private String stageDetection;

    /** Component 3: Per-stage entry conditions — anticipatory and confirmation, SL logic, TP targets. */
    @Lob
    @Column(name = "entry_rules", columnDefinition = "TEXT")
    private String entryRules;

    /** Component 4: What each indicator should show at each stage. What confirms. What contradicts. */
    @Lob
    @Column(name = "indicator_rules", columnDefinition = "TEXT")
    private String indicatorRules;

    /** Component 5: What price action kills this pattern. Specific level or structure triggers. */
    @Lob
    @Column(name = "invalidation_rules", columnDefinition = "TEXT")
    private String invalidationRules;

    /** Component 6: Targeted questions to ask the expert when ambiguity is detected. */
    @Lob
    @Column(name = "ambiguity_questions", columnDefinition = "TEXT")
    private String ambiguityQuestions;

    /** Component 7: Cross-timeframe verification rules — what must be true on OTHER timeframes. */
    @Lob
    @Column(name = "cross_verification_rules", columnDefinition = "TEXT")
    private String crossVerificationRules;

    /** Whether this skill is active and eligible to be loaded by the orchestrator */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /** System-seeded skills (Triangle, Wave 4) that come pre-populated */
    @Column(name = "is_system_seed", nullable = false)
    @Builder.Default
    private Boolean isSystemSeed = false;

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
