package com.dtech.kitecon.service.copilot;

import com.dtech.kitecon.data.copilot.CopilotSkill;
import com.dtech.kitecon.repository.copilot.CopilotSkillRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CopilotSkillService {

    private final CopilotSkillRepository skillRepository;

    public List<CopilotSkill> getAllSkillsForUser(Long userId) {
        return skillRepository.findByUserIdAndIsActiveTrue(userId);
    }

    public List<CopilotSkill> getSkillsByCategory(Long userId, String category) {
        return skillRepository.findByUserIdAndCategoryAndIsActiveTrue(userId, category);
    }

    public Optional<CopilotSkill> getSkillByKey(Long userId, String skillKey) {
        return skillRepository.findByUserIdAndSkillKey(userId, skillKey);
    }

    public Optional<CopilotSkill> getSkillById(Long id) {
        return skillRepository.findById(id);
    }

    @Transactional
    public CopilotSkill saveSkill(CopilotSkill skill) {
        return skillRepository.save(skill);
    }

    @Transactional
    public void deleteSkill(Long id) {
        skillRepository.findById(id).ifPresent(s -> {
            s.setIsActive(false);
            skillRepository.save(s);
        });
    }

    /**
     * Build a prompt-ready representation of a skill.
     * All 7 components are concatenated with section headers.
     * This is what gets passed to the AI alongside the investigation context.
     */
    public String buildSkillPrompt(CopilotSkill skill) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== SKILL: ").append(skill.getName()).append(" ===\n");
        sb.append("Category: ").append(skill.getCategory()).append("\n\n");

        appendSection(sb, "1. IDENTIFICATION RULES", skill.getIdentificationRules());
        appendSection(sb, "2. STAGE DETECTION", skill.getStageDetection());
        appendSection(sb, "3. ENTRY RULES PER STAGE", skill.getEntryRules());
        appendSection(sb, "4. INDICATOR RULES PER STAGE", skill.getIndicatorRules());
        appendSection(sb, "5. INVALIDATION RULES", skill.getInvalidationRules());
        appendSection(sb, "6. AMBIGUITY QUESTIONS", skill.getAmbiguityQuestions());
        appendSection(sb, "7. CROSS-VERIFICATION RULES", skill.getCrossVerificationRules());

        return sb.toString();
    }

    private void appendSection(StringBuilder sb, String header, String content) {
        sb.append("--- ").append(header).append(" ---\n");
        sb.append(content != null ? content : "(Not yet populated)").append("\n\n");
    }

    /**
     * Seed the Triangle Skill and Wave 4 Skill for a new user.
     * These are Phase 1 demonstration skills with partial content.
     */
    @Transactional
    public void seedDemoSkillsForUser(Long userId) {
        if (!skillRepository.findByUserIdAndSkillKey(userId, "triangle").isEmpty()) return;

        skillRepository.save(buildTriangleSkill(userId));
        skillRepository.save(buildWave4Skill(userId));
    }

    private Optional<CopilotSkill> findByUserIdAndSkillKey(Long userId, String key) {
        return skillRepository.findByUserIdAndSkillKey(userId, key);
    }

    // ─── Seeded skill content (partial, per spec Section 13.2) ───────────────

    private CopilotSkill buildTriangleSkill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Triangle Skill")
                .description("Symmetrical, ascending, and descending triangle patterns — ABCDE structure")
                .category("PATTERN")
                .skillKey("triangle")
                .isSystemSeed(true)
                .identificationRules("""
                    A triangle is identified by converging trendlines connecting swing highs and lows.
                    Required: minimum 5 touches (2 on one side, 3 on the other, or alternating).
                    Structure: 5-leg corrective — A, B, C, D, E.
                    Each leg must be a 3-wave corrective structure (not a 5-wave impulse).
                    The pattern must be contracting — each swing smaller than the previous.
                    Symmetrical: both upper and lower trendlines converging.
                    Ascending: flat upper, rising lower.
                    Descending: flat lower, declining upper.
                    """)
                .stageDetection("""
                    Determine current stage by counting completed legs from the origin:
                    - After leg A: Stage A_COMPLETE — watching for B reversal
                    - After leg B: Stage B_COMPLETE — watching for C reversal
                    - After leg C: Stage C_COMPLETE — watching for D reversal
                    - After leg D: Stage D_COMPLETE — approaching E point (anticipatory entry zone)
                    - After leg E completes: BREAKOUT expected — confirmation entry zone
                    Current stage = count of completed corrective legs from the triangle origin.
                    """)
                .entryRules("""
                    ANTICIPATORY ENTRY (Stage D_COMPLETE / approaching E):
                    - Entry zone: E point projection (intersection of lower trendline)
                    - SL: just beyond the E point (below for bullish triangle)
                    - TP: measured move = widest part of triangle added to breakout point
                    - Requires lower TF (15min) confirmation: Stochastic extreme + reversal candle

                    CONFIRMATION ENTRY (post-breakout retest):
                    - Entry: on pullback to broken trendline
                    - SL: below the retest candle low
                    - TP: measured move from breakout
                    - Requires: broken trendline holds as support on retest
                    """)
                .indicatorRules("""
                    MACD: Must be COMPRESSING during triangle formation. Histogram contracting.
                    If MACD is expanding — flag immediately (potential impulse, not triangle).

                    RSI: Should be in mid-range (40-60) during triangle — coiling, not extreme.
                    Extremes during triangle suggest the pattern may be misidentified.

                    Stochastic: At E point — should be at extreme (oversold for bullish, overbought for bearish).
                    Crossover from extreme = entry trigger for anticipatory entry.

                    Volume: Should decline as triangle progresses. Expansion at breakout confirms.
                    """)
                .invalidationRules("""
                    INVALIDATED if:
                    - Price closes beyond the E point by more than 1x ATR (false E, pattern failed)
                    - Any leg shows a 5-wave impulse sub-structure (leg is not corrective — not a triangle)
                    - MACD expands aggressively during the supposed triangle
                    - B retracement exceeds 78.6% of A (too deep — consider zigzag instead)
                    - The converging trendlines are violated by more than 2% before completion
                    """)
                .ambiguityQuestions("""
                    Q1: Is leg [X] complete or still extending? Sub-structure on 1h shows only 2 waves — could be extending.
                    Q2: Is this a triangle or a channel? Trendlines appear near-parallel — confirm convergence.
                    Q3: Wave count shows this could be Wave 4 triangle or Wave 2 triangle — which degree is this?
                    Q4: B retracement is 71% of A — deep for triangle. Confirm this is corrective, not impulsive.
                    """)
                .crossVerificationRules("""
                    CV-1: MACD must be COMPRESSING on the trading TF.
                    If MACD is expanding → Flag: 'MACD expanding contradicts triangle thesis. This may be an impulse.'

                    CV-2: All triangle legs must show 3-wave corrective sub-structure on lower TF.
                    If any leg shows 5-wave impulse → Flag: 'Leg [X] appears impulsive. Triangle leg cannot be 5-wave.'

                    CV-3: B retracement depth must fit triangle rules (< 78.6% of A).
                    If B > 78.6% → Flag: 'Deep B retracement unusual. Consider zigzag with extended C.'

                    CV-4: Check daily/weekly for wave context. Triangle expected in Wave 4 (most common) or Wave 1 leading.
                    If daily shows Wave 3 impulse still in progress → Flag: 'Triangle premature — daily Wave 3 may not be complete.'
                    """)
                .build();
    }

    private CopilotSkill buildWave4Skill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Wave 4 Skill")
                .description("Elliott Wave 4 — corrective phase rules, expected patterns, and cross-verification")
                .category("WAVE")
                .skillKey("wave_4")
                .isSystemSeed(true)
                .identificationRules("""
                    Wave 4 is a corrective wave following a Wave 3 impulse.
                    Required: Wave 3 must be confirmed complete (5-wave structure on lower TF).
                    Wave 4 CANNOT overlap Wave 1 price territory (non-overlap rule).
                    Exception: ending diagonals in Wave 5 or Wave C only.
                    Wave 4 is typically shallower than Wave 2 (38.2%–61.8% of Wave 3).
                    Wave 4 is often complex, time-consuming, and frustrating.
                    FORBIDDEN: Wave 4 cannot be a simple 5-wave impulse.
                    """)
                .stageDetection("""
                    Determine stage based on correction pattern within Wave 4:
                    - If Triangle: apply Triangle Skill stage detection (A through E)
                    - If Flat: A complete, B complete (deep retracement), C forming/complete
                    - If Zigzag: A complete (impulse down), B complete (partial retrace), C forming
                    - If Complex (WXY): W complete, connector X, Y forming
                    Stage = position within the correction structure identified.
                    """)
                .entryRules("""
                    Wave 4 itself is NOT an entry — it is context for a Wave 5 entry.

                    ANTICIPATORY ENTRY for Wave 5 (at Wave 4 end zone):
                    - Entry zone: 38.2%–61.8% retracement of Wave 3
                    - Fibonacci confluence: 38.2%, 50%, or 61.8% retracement
                    - SL: below Wave 4 low (or Wave 1 high if in doubt)
                    - TP: 1.618x extension of Wave 1 from Wave 4 end (Wave 5 target)
                    - Requires: Wave 4 correction showing exhaustion (Stochastic extreme, RSI support)

                    CONFIRMATION ENTRY for Wave 5:
                    - Entry: after Wave 4 pattern completes and price impulsively reclaims Wave 3 territory
                    - Requires: 5-wave impulse beginning on lower TF (15min/1h)
                    """)
                .indicatorRules("""
                    MACD: Must be COMPRESSING during Wave 4. Lower histogram highs.
                    If MACD makes a new high during Wave 4 → Flag: 'Wave 4 MACD expansion unusual — check if Wave 3 is truly complete.'

                    RSI: Should be declining/consolidating. RSI should not make new highs.
                    RSI divergence between Wave 3 and Wave 5 (lower RSI on Wave 5) = exhaustion warning.

                    Stochastic: At Wave 4 end — should be oversold (bullish) before Wave 5 begins.
                    Stochastic crossover from oversold zone = lower TF entry trigger for Wave 5.
                    """)
                .invalidationRules("""
                    INVALIDATED (Wave 4 interpretation fails) if:
                    - Price closes below Wave 1 high (non-overlap rule violated)
                      Exception: only if ending diagonal structure is confirmed
                    - Wave 4 retracement exceeds 78.6% of Wave 3 (too deep — likely Wave 2, not Wave 4)
                    - Wave 4 shows a 5-wave impulse sub-structure (Wave 4 cannot be impulsive)
                    - MACD makes a new high during Wave 4 (suggests Wave 3 extension, not completion)
                    """)
                .ambiguityQuestions("""
                    Q1: Is Wave 3 truly complete? I see a possible extended Wave 3 with a nested 1-2-3 inside.
                    Could this be Wave 3 extension with Waves 4 and 5 still pending?

                    Q2: The retracement is 72% of Wave 3 — deeper than typical Wave 4.
                    Should we consider this is Wave 2 at a higher degree instead?

                    Q3: The correction appears complex (WXY). Can you confirm the W and X legs?

                    Q4: Non-overlap rule check — please confirm Wave 4 has not closed into Wave 1 territory.
                    """)
                .crossVerificationRules("""
                    CV-1: Verify Wave 3 was truly impulsive before accepting Wave 4.
                    Check 1h for 5-wave sub-structure in Wave 3. If only 3 waves visible → Flag: 'Wave 3 may not be complete.'

                    CV-2: Non-overlap rule must hold.
                    If current price is in Wave 1 territory → Flag: 'Non-overlap violated. Not a valid Wave 4 unless ending diagonal.'

                    CV-3: MACD must be compressing.
                    If MACD expanding → Flag: 'MACD expansion during Wave 4 contradicts thesis.'

                    CV-4: Fibonacci retracement check.
                    Wave 4 end should land at 38.2%, 50%, or 61.8% of Wave 3.
                    If Wave 4 ends at random level with no Fibonacci confluence → Flag: 'No Fibonacci support at Wave 4 end. Confidence layer 3 fails.'

                    CV-5: Check the higher timeframe wave structure.
                    Wave 4 on daily corresponds to what on weekly? Nested structure should be consistent.
                    """)
                .build();
    }
}
