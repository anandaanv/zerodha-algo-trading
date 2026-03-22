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

    /** Get active scan skills (all categories except REASONING). */
    public List<CopilotSkill> getScanSkillsForUser(Long userId) {
        return skillRepository.findByUserIdAndIsActiveTrue(userId).stream()
                .filter(s -> !"REASONING".equals(s.getCategory()))
                .toList();
    }

    /** Get active reasoning skills (category = REASONING). */
    public List<CopilotSkill> getReasoningSkillsForUser(Long userId) {
        return skillRepository.findByUserIdAndCategoryAndIsActiveTrue(userId, "REASONING");
    }

    /**
     * Build a scan-mode prompt — only includes detection-relevant sections.
     * Skips Entry Rules (3) and Indicator Rules (4) since scan is observe-only.
     */
    public String buildScanSkillPrompt(CopilotSkill skill) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== SKILL: ").append(skill.getName()).append(" ===\n");
        sb.append("Category: ").append(skill.getCategory()).append("\n\n");

        appendSection(sb, "1. IDENTIFICATION RULES", skill.getIdentificationRules());
        appendSection(sb, "2. STAGE DETECTION", skill.getStageDetection());
        appendSection(sb, "5. INVALIDATION RULES", skill.getInvalidationRules());
        appendSection(sb, "7. CROSS-VERIFICATION RULES", skill.getCrossVerificationRules());

        return sb.toString();
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

    /**
     * Seed reasoning skills (confluence_checker, multi_timeframe_correlator) for a user.
     */
    @Transactional
    public void seedReasoningSkillsForUser(Long userId) {
        if (skillRepository.findByUserIdAndSkillKey(userId, "confluence_checker").isPresent()) return;

        skillRepository.save(CopilotSkill.builder()
                .userId(userId)
                .name("Confluence Checker")
                .description("Cross-correlate observations from different skill categories to find directional confluence")
                .category("REASONING")
                .skillKey("confluence_checker")
                .isSystemSeed(true)
                .identificationRules("""
                    Look for 2+ observations from different skill categories pointing to the same directional bias.
                    A valid confluence requires at least one PATTERN observation and one WAVE observation,
                    both with MEDIUM or HIGH confidence.
                    """)
                .stageDetection("""
                    Assess whether confluence is early (just forming), mature (multiple confirmations),
                    or diverging (observations starting to contradict).
                    """)
                .entryRules("""
                    Require at least one PATTERN + one WAVE observation, both MED or HIGH confidence.
                    If only one category has positive observations, flag as insufficient confluence.
                    """)
                .indicatorRules("""
                    Cross-check indicator observations — contradicting indicator observations downgrade confluence.
                    """)
                .invalidationRules("""
                    If pattern and wave observations point in opposite directions, no hypothesis should be created.
                    If all observations are LOW confidence, no hypothesis.
                    """)
                .ambiguityQuestions("""
                    Which pattern takes priority when timeframes disagree?
                    How should conflicting wave counts across timeframes be reconciled?
                    """)
                .crossVerificationRules("""
                    Higher timeframe observation takes priority in directional conflicts.
                    A lower timeframe observation can refine entry timing but cannot override
                    the higher timeframe directional bias.
                    """)
                .build());

        skillRepository.save(CopilotSkill.builder()
                .userId(userId)
                .name("Multi-Timeframe Correlator")
                .description("Verify that lower timeframe observations support the higher timeframe thesis")
                .category("REASONING")
                .skillKey("multi_timeframe_correlator")
                .isSystemSeed(true)
                .identificationRules("""
                    Examine observations across all timeframes. Higher TF sets the directional thesis.
                    Lower TF observations must either support or refine the higher TF thesis.
                    """)
                .stageDetection("""
                    Compare stages across timeframes. A higher TF at stage D_COMPLETE should see
                    lower TF showing early impulse or reversal patterns in the expected direction.
                    """)
                .entryRules("""
                    Entry is valid only when the lower TF observation supports the higher TF direction.
                    Use lower TF observation timing for precision entry.
                    """)
                .indicatorRules("""
                    Higher TF indicators set the context. Lower TF indicators provide entry timing.
                    Contradiction between TF indicators downgrades the setup.
                    """)
                .invalidationRules("""
                    If lower TF shows strong impulse AGAINST the higher TF thesis direction,
                    the setup is invalidated regardless of higher TF pattern.
                    """)
                .ambiguityQuestions("""
                    Which timeframe should be primary for this particular setup?
                    Is the lower TF move a temporary pullback or a genuine reversal?
                    """)
                .crossVerificationRules("""
                    The higher TF must show a clear pattern or wave before lower TF entries are considered.
                    Lower TF confirmation must appear within the expected time window for the higher TF stage.
                    """)
                .build());
    }

    /**
     * Seed all chart pattern skills for a user.
     * Skips any skill whose key already exists for this user.
     */
    @Transactional
    public int seedChartPatternSkillsForUser(Long userId) {
        int count = 0;
        for (CopilotSkill skill : buildAllChartPatternSkills(userId)) {
            if (skillRepository.findByUserIdAndSkillKey(userId, skill.getSkillKey()).isEmpty()) {
                skillRepository.save(skill);
                count++;
            }
        }
        return count;
    }

    private List<CopilotSkill> buildAllChartPatternSkills(Long userId) {
        return List.of(
            buildDoubleBottomSkill(userId),
            buildDoubleTopSkill(userId),
            buildHeadAndShouldersSkill(userId),
            buildInverseHeadAndShouldersSkill(userId),
            buildAscendingTriangleSkill(userId),
            buildDescendingTriangleSkill(userId),
            buildSymmetricalTriangleSkill(userId),
            buildRisingWedgeSkill(userId),
            buildFallingWedgeSkill(userId),
            buildBullFlagSkill(userId),
            buildBearFlagSkill(userId),
            buildAscendingChannelSkill(userId),
            buildDescendingChannelSkill(userId),
            buildCupAndHandleSkill(userId),
            buildTripleTopSkill(userId),
            buildTripleBottomSkill(userId),
            buildRoundingBottomSkill(userId),
            buildBroadeningSKill(userId)
        );
    }

    // ─── Chart Pattern Skill Builders ─────────────────────────────────────────

    private CopilotSkill buildDoubleBottomSkill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Double Bottom")
                .description("Classic double bottom (W pattern) — bullish reversal")
                .category("PATTERN")
                .skillKey("double_bottom")
                .isSystemSeed(true)
                .identificationRules("""
                    A double bottom is a bullish reversal pattern forming a "W" shape.
                    Required: Two distinct lows at approximately the same price level (within 1-2% tolerance).
                    Between the two lows, there must be a rally forming the "neckline" high.
                    The second low should hold at or slightly above the first low level.
                    Volume typically decreases on the second low compared to the first.
                    Pattern is confirmed when price breaks above the neckline with conviction.
                    Also detect recently completed double bottoms where the neckline has already broken —
                    the pattern is confirmed and a long trade may be active.
                    """)
                .stageDetection("""
                    - FIRST_LOW: Initial decline completed, first bounce starting
                    - NECKLINE_RALLY: Price bounced from first low, forming interim high
                    - SECOND_LOW: Price declining toward first low area — watching for support hold
                    - BREAKOUT_PENDING: Second low held, price rallying toward neckline
                    - CONFIRMED: Price broke above neckline — pattern confirmed
                    - TRADE_ACTIVE: Price above neckline, measured move target in play
                    """)
                .entryRules("""
                    ANTICIPATORY ENTRY (at second low / support hold):
                    - Entry zone: at or near the first low price level
                    - SL: below the lower of the two lows (1-2% buffer)
                    - TP: neckline + (neckline - low) = measured move target
                    - Requires: lower TF reversal candle + volume pick-up

                    CONFIRMATION ENTRY (neckline breakout + retest):
                    - Entry: on pullback to neckline after breakout
                    - SL: below neckline
                    - TP: measured move from breakout point
                    - Requires: neckline holds as support on retest
                    """)
                .indicatorRules("""
                    RSI: Bullish divergence between first and second low (lower price, higher RSI) is strong confirmation.
                    MACD: Decreasing bearish momentum on the second low.
                    Volume: Lower on second low than first — selling exhaustion.
                    Volume expansion on neckline break confirms.
                    """)
                .invalidationRules("""
                    INVALIDATED if:
                    - Second low breaks significantly below the first low (>2%)
                    - Price fails to rally from second low zone within reasonable timeframe
                    - Neckline breakout immediately sold back into (failed breakout)
                    - RSI makes lower lows on both attempts (no divergence — trend continuation)
                    """)
                .ambiguityQuestions("""
                    Q1: Are the two lows close enough in price to qualify?
                    Q2: Is the timeframe between the two lows reasonable?
                    Q3: Could this be a continuation pattern rather than a reversal?
                    """)
                .crossVerificationRules("""
                    CV-1: Higher TF must show the decline is mature (not early in a new downtrend).
                    CV-2: Volume profile should support accumulation at the double bottom zone.
                    CV-3: Key support levels from higher TF should align with the bottom zone.
                    """)
                .build();
    }

    private CopilotSkill buildDoubleTopSkill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Double Top")
                .description("Classic double top (M pattern) — bearish reversal")
                .category("PATTERN")
                .skillKey("double_top")
                .isSystemSeed(true)
                .identificationRules("""
                    A double top is a bearish reversal pattern forming an "M" shape.
                    Required: Two distinct highs at approximately the same price level (within 1-2% tolerance).
                    Between the two highs, there must be a pullback forming the "neckline" low.
                    The second high should fail at or slightly below the first high level.
                    Volume typically decreases on the second high compared to the first.
                    Pattern confirmed when price breaks below the neckline.
                    Also detect recently completed double tops where breakdown has occurred.
                    """)
                .stageDetection("""
                    - FIRST_HIGH: Initial rally completed, first pullback starting
                    - NECKLINE_PULLBACK: Price pulled back from first high, forming interim low
                    - SECOND_HIGH: Price rallying toward first high — watching for resistance
                    - BREAKDOWN_PENDING: Second high failed, price declining toward neckline
                    - CONFIRMED: Price broke below neckline — pattern confirmed
                    - BREAKDOWN_ACTIVE: Price below neckline, measured move target in play
                    """)
                .entryRules("""
                    ANTICIPATORY ENTRY (at second high / resistance failure):
                    - Entry zone: at or near the first high price level (short)
                    - SL: above the higher of the two highs (1-2% buffer)
                    - TP: neckline - (high - neckline) = measured move target
                    - Requires: lower TF bearish reversal candle + volume fade

                    CONFIRMATION ENTRY (neckline breakdown + retest):
                    - Entry: on pullback to neckline after breakdown (short)
                    - SL: above neckline
                    - TP: measured move from breakdown point
                    - Requires: neckline holds as resistance on retest
                    """)
                .indicatorRules("""
                    RSI: Bearish divergence (equal/higher price, lower RSI on second high).
                    MACD: Decreasing bullish momentum on second high.
                    Volume: Lower on second high — buying exhaustion.
                    Volume expansion on neckline break confirms.
                    """)
                .invalidationRules("""
                    INVALIDATED if:
                    - Second high breaks significantly above the first (>2%) with conviction
                    - Neckline breakdown immediately bought back (failed breakdown)
                    - RSI makes higher highs on both attempts (no divergence)
                    """)
                .ambiguityQuestions("""
                    Q1: Are the two highs close enough to qualify?
                    Q2: Could this be a flag/pennant continuation rather than reversal?
                    """)
                .crossVerificationRules("""
                    CV-1: Higher TF must show the rally is mature (not early in uptrend).
                    CV-2: Volume profile should show distribution at the top zone.
                    CV-3: Key resistance from higher TF should align with the top zone.
                    """)
                .build();
    }

    private CopilotSkill buildHeadAndShouldersSkill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Head and Shoulders")
                .description("Head and shoulders top — bearish reversal with left shoulder, head, right shoulder")
                .category("PATTERN")
                .skillKey("head_and_shoulders")
                .isSystemSeed(true)
                .identificationRules("""
                    Classic bearish reversal with three peaks: left shoulder, head (highest), right shoulder.
                    The neckline connects the two troughs between the three peaks.
                    Left shoulder and right shoulder should be at roughly similar height.
                    Head must be clearly higher than both shoulders.
                    Neckline can be horizontal or slightly sloped (ascending neckline is more bearish).
                    Volume: typically highest on left shoulder, decreasing on head and right shoulder.
                    Pattern confirmed when price breaks below the neckline.
                    """)
                .stageDetection("""
                    - LEFT_SHOULDER: First peak formed, first trough forming
                    - HEAD_FORMING: Rally above left shoulder peak — head in progress
                    - HEAD_COMPLETE: Head peaked and pulling back to neckline area
                    - RIGHT_SHOULDER: Rally from neckline — should fail below head level
                    - BREAKDOWN_PENDING: Right shoulder formed, declining toward neckline
                    - CONFIRMED: Price broke below neckline
                    """)
                .entryRules("""
                    ANTICIPATORY (right shoulder formation):
                    - Entry zone: right shoulder peak area (short)
                    - SL: above the head
                    - TP: neckline - (head - neckline) = measured move
                    - Requires: RSI divergence, volume fade on right shoulder

                    CONFIRMATION (neckline break + retest):
                    - Entry: pullback to neckline after breakdown
                    - SL: above neckline
                    - TP: measured move from breakdown point
                    """)
                .indicatorRules("""
                    RSI: Bearish divergence — head makes higher high but RSI makes lower high.
                    Volume: Declining from left shoulder → head → right shoulder.
                    MACD: Should show weakening momentum on each successive peak.
                    """)
                .invalidationRules("""
                    INVALIDATED if:
                    - Right shoulder exceeds head height
                    - Neckline break fails and price rallies to new highs
                    - Volume expands strongly on right shoulder (buying not exhausted)
                    """)
                .ambiguityQuestions("""
                    Q1: Is the right shoulder complete or still forming?
                    Q2: Is the neckline slope too steep to be reliable?
                    """)
                .crossVerificationRules("""
                    CV-1: Higher TF should show mature uptrend approaching exhaustion.
                    CV-2: Neckline should align with meaningful support levels.
                    CV-3: Volume must decline across the three peaks.
                    """)
                .build();
    }

    private CopilotSkill buildInverseHeadAndShouldersSkill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Inverse Head and Shoulders")
                .description("Inverse H&S bottom — bullish reversal with three troughs")
                .category("PATTERN")
                .skillKey("inverse_head_and_shoulders")
                .isSystemSeed(true)
                .identificationRules("""
                    Bullish reversal with three troughs: left shoulder, head (lowest), right shoulder.
                    Neckline connects the two peaks between the three troughs.
                    Both shoulders at roughly similar depth. Head must be clearly lower.
                    Volume: often increases on the right shoulder rally and neckline break.
                    Pattern confirmed when price breaks above the neckline.
                    """)
                .stageDetection("""
                    - LEFT_SHOULDER: First trough formed, first rally forming
                    - HEAD_FORMING: Decline below left shoulder — head forming
                    - HEAD_COMPLETE: Head bottomed and rallying toward neckline
                    - RIGHT_SHOULDER: Pullback from neckline — should hold above head
                    - BREAKOUT_PENDING: Right shoulder formed, rallying toward neckline
                    - CONFIRMED: Price broke above neckline
                    """)
                .entryRules("""
                    ANTICIPATORY (right shoulder support hold):
                    - Entry zone: right shoulder trough area (long)
                    - SL: below the head
                    - TP: neckline + (neckline - head) = measured move

                    CONFIRMATION (neckline breakout + retest):
                    - Entry: pullback to neckline after breakout
                    - SL: below neckline
                    - TP: measured move from breakout point
                    """)
                .indicatorRules("""
                    RSI: Bullish divergence — head makes lower low but RSI makes higher low.
                    Volume: Should increase on right shoulder rally and neckline break.
                    MACD: Momentum should improve on each successive trough.
                    """)
                .invalidationRules("""
                    INVALIDATED if right shoulder breaks below head, or neckline break fails.
                    """)
                .ambiguityQuestions("Q1: Is the right shoulder complete? Q2: Is neckline too steep?")
                .crossVerificationRules("""
                    CV-1: Higher TF should show mature downtrend near exhaustion.
                    CV-2: Neckline should align with meaningful resistance.
                    """)
                .build();
    }

    private CopilotSkill buildAscendingTriangleSkill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Ascending Triangle")
                .description("Ascending triangle — flat resistance with rising support, typically bullish")
                .category("PATTERN")
                .skillKey("ascending_triangle")
                .isSystemSeed(true)
                .identificationRules("""
                    Flat horizontal resistance with rising lows forming an ascending lower trendline.
                    Minimum 2 touches on the flat resistance and 2 rising lows.
                    Each low must be higher than the previous low.
                    Buyers increasingly aggressive — willing to buy at higher prices.
                    Typically bullish — breaks upward 70%+ of the time.
                    Can also be a bearish trap if it fails the resistance and reverses.
                    """)
                .stageDetection("""
                    - FORMING: First two touches on resistance and at least one rising low
                    - MATURE: 3+ touches on resistance, rising lows compressing toward resistance
                    - BREAKOUT_IMMINENT: Price coiling tightly near resistance, multiple rejections
                    - CONFIRMED: Breakout above resistance with volume
                    """)
                .entryRules("""
                    ANTICIPATORY (rising support bounce):
                    - Entry: at rising trendline support
                    - SL: below the rising trendline
                    - TP: flat resistance level, then measured move if breakout occurs

                    CONFIRMATION (breakout above resistance):
                    - Entry: on breakout above flat resistance or pullback to broken resistance
                    - SL: below the last higher low
                    - TP: height of triangle projected from breakout point
                    """)
                .indicatorRules("""
                    Volume: Should decline during formation and expand on breakout.
                    RSI: Should remain above 40 during formation (bullish bias).
                    MACD: Compression during triangle, expansion on breakout.
                    """)
                .invalidationRules("""
                    INVALIDATED if: price breaks below the rising trendline,
                    or fails to break resistance and makes a lower low.
                    """)
                .ambiguityQuestions("Q1: Is the flat resistance truly flat or slightly declining? Q2: Is this late in a trend where it could be distribution?")
                .crossVerificationRules("""
                    CV-1: Higher TF trend should support bullish resolution.
                    CV-2: Volume should confirm accumulation pattern.
                    """)
                .build();
    }

    private CopilotSkill buildDescendingTriangleSkill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Descending Triangle")
                .description("Descending triangle — flat support with declining highs, typically bearish")
                .category("PATTERN")
                .skillKey("descending_triangle")
                .isSystemSeed(true)
                .identificationRules("""
                    Flat horizontal support with declining highs forming a descending upper trendline.
                    Minimum 2 touches on flat support and 2 declining highs.
                    Each high must be lower than the previous high.
                    Sellers increasingly aggressive — willing to sell at lower prices.
                    Typically bearish — breaks downward 70%+ of the time.
                    """)
                .stageDetection("""
                    - FORMING: First two touches on support and at least one lower high
                    - MATURE: 3+ touches on support, lower highs compressing toward support
                    - BREAKDOWN_IMMINENT: Price coiling tightly near support
                    - CONFIRMED: Breakdown below support with volume
                    """)
                .entryRules("""
                    ANTICIPATORY (declining resistance bounce short):
                    - Entry: at descending trendline resistance (short)
                    - SL: above the descending trendline
                    - TP: flat support level, then measured move if breakdown

                    CONFIRMATION (breakdown below support):
                    - Entry: on breakdown or pullback to broken support (short)
                    - SL: above the last lower high
                    - TP: height of triangle projected from breakdown point
                    """)
                .indicatorRules("""
                    Volume: Decline during formation, expand on breakdown.
                    RSI: Should stay below 60 (bearish bias).
                    """)
                .invalidationRules("INVALIDATED if price breaks above the descending trendline or makes a higher high.")
                .ambiguityQuestions("Q1: Is the flat support truly flat? Q2: Could this be accumulation rather than distribution?")
                .crossVerificationRules("CV-1: Higher TF trend should support bearish resolution. CV-2: Check if support aligns with major higher TF support — strong confluence may cause bounce instead.")
                .build();
    }

    private CopilotSkill buildSymmetricalTriangleSkill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Symmetrical Triangle")
                .description("Symmetrical triangle — converging trendlines, continuation in trend direction")
                .category("PATTERN")
                .skillKey("symmetrical_triangle")
                .isSystemSeed(true)
                .identificationRules("""
                    Both upper and lower trendlines converging — declining highs and rising lows.
                    Minimum 2 touches on each trendline (4 total contact points).
                    Typically a continuation pattern — breaks in the direction of the prior trend.
                    Can break either way — prior trend direction gives probabilistic edge.
                    Price should be within the last 25-75% of triangle apex for meaningful breakout.
                    """)
                .stageDetection("""
                    - EARLY: First 2-3 swings established, trendlines drawn
                    - MATURE: 4+ swings, compression visible, volume declining
                    - APEX_APPROACH: Price near the apex intersection, breakout imminent
                    - CONFIRMED: Clear break above upper or below lower trendline
                    """)
                .entryRules("""
                    ANTICIPATORY: Trade in the direction of prior trend at trendline bounce.
                    CONFIRMATION: Wait for breakout with volume > 1.5x average.
                    Measured move target = widest part of triangle added to breakout point.
                    """)
                .indicatorRules("Volume must decline during formation. MACD should compress. RSI near 50.")
                .invalidationRules("INVALIDATED if breakout fails (price re-enters triangle) or pattern exceeds 75% of apex distance without breaking.")
                .ambiguityQuestions("Q1: Which direction is the prior trend? Q2: Is this a continuation or a reversal at a key level?")
                .crossVerificationRules("CV-1: Prior trend direction gives bias. CV-2: Volume on breakout must confirm. CV-3: Check if apex aligns with time-based cycle.")
                .build();
    }

    private CopilotSkill buildRisingWedgeSkill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Rising Wedge")
                .description("Rising wedge — converging upward-sloping trendlines, bearish")
                .category("PATTERN")
                .skillKey("rising_wedge")
                .isSystemSeed(true)
                .identificationRules("""
                    Both upper and lower trendlines slope upward but converge (upper trendline less steep).
                    Higher highs and higher lows but with decreasing momentum.
                    Each successive rally is weaker than the previous.
                    Bearish pattern — typically resolves with a sharp breakdown.
                    In an uptrend: reversal signal. In a downtrend: continuation (bear flag variant).
                    Minimum 3 touches on each trendline.
                    """)
                .stageDetection("""
                    - FORMING: Two touches on each trendline, upward wedge visible
                    - MATURE: 3+ touches each side, convergence clear, momentum fading
                    - BREAKDOWN_PENDING: Price near wedge apex, weak bounces off lower trendline
                    - CONFIRMED: Price breaks below the lower (rising) trendline
                    """)
                .entryRules("""
                    ANTICIPATORY: Short at upper trendline with SL above it.
                    CONFIRMATION: Short on break below lower trendline, TP = height of wedge entry point.
                    """)
                .indicatorRules("RSI: Bearish divergence (higher prices, lower RSI). Volume: Declining throughout formation. MACD: Momentum compression or bearish divergence.")
                .invalidationRules("INVALIDATED if price breaks above the upper trendline with volume and acceleration.")
                .ambiguityQuestions("Q1: Is this a rising wedge or a steep ascending channel? Q2: Is the trend mature enough for reversal?")
                .crossVerificationRules("CV-1: Higher TF should show resistance approaching. CV-2: Check RSI divergence for confirmation.")
                .build();
    }

    private CopilotSkill buildFallingWedgeSkill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Falling Wedge")
                .description("Falling wedge — converging downward-sloping trendlines, bullish")
                .category("PATTERN")
                .skillKey("falling_wedge")
                .isSystemSeed(true)
                .identificationRules("""
                    Both trendlines slope downward but converge (lower trendline less steep).
                    Lower lows and lower highs but with decreasing selling momentum.
                    Each decline is weaker than the previous.
                    Bullish pattern — resolves with a breakout upward.
                    In a downtrend: reversal signal. In an uptrend: continuation (bull flag variant).
                    Minimum 3 touches on each trendline.
                    """)
                .stageDetection("""
                    - FORMING: Two touches on each trendline, downward wedge visible
                    - MATURE: 3+ touches each side, convergence clear, selling exhaustion
                    - BREAKOUT_PENDING: Near apex, strong bounces off lower trendline
                    - CONFIRMED: Price breaks above the upper (falling) trendline
                    """)
                .entryRules("""
                    ANTICIPATORY: Long at lower trendline with SL below it.
                    CONFIRMATION: Long on break above upper trendline, TP = height of wedge from breakout.
                    """)
                .indicatorRules("RSI: Bullish divergence (lower prices, higher RSI). Volume: Declining. MACD: Bullish divergence or compression.")
                .invalidationRules("INVALIDATED if price breaks below the lower trendline with volume.")
                .ambiguityQuestions("Q1: Is this a falling wedge or a descending channel? Q2: Is selling exhaustion confirmed by indicators?")
                .crossVerificationRules("CV-1: Higher TF should show support approaching. CV-2: RSI divergence must be present.")
                .build();
    }

    private CopilotSkill buildBullFlagSkill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Bull Flag")
                .description("Bull flag — sharp rally (pole) followed by shallow downward-sloping consolidation")
                .category("PATTERN")
                .skillKey("bull_flag")
                .isSystemSeed(true)
                .identificationRules("""
                    A strong impulsive rally forms the "pole" (typically 5-20% move).
                    Followed by a shallow, downward-sloping consolidation channel — the "flag".
                    Flag retraces 20-50% of the pole (shallow). Deep retraces invalidate.
                    Flag should contain parallel or near-parallel declining trendlines.
                    Duration: flag is typically 1/3 to 1/2 the time of the pole.
                    Volume declines during flag formation.
                    Continuation pattern — expects another move equal to the pole after breakout.
                    """)
                .stageDetection("""
                    - POLE_COMPLETE: Sharp rally done, consolidation beginning
                    - FLAG_FORMING: Shallow pullback in progress, 1-3 swings within channel
                    - BREAKOUT_PENDING: Flag mature, price near upper flag boundary
                    - CONFIRMED: Breakout above upper flag trendline with volume
                    """)
                .entryRules("""
                    ANTICIPATORY: Long at lower flag boundary (support) with SL below flag.
                    CONFIRMATION: Long on breakout above upper flag boundary.
                    TP = pole height projected from breakout point.
                    """)
                .indicatorRules("Volume: Must decline in flag. MACD: Should remain bullish but compress. RSI: Should stay above 40.")
                .invalidationRules("INVALIDATED if flag retraces more than 50% of pole, or price breaks below the flag channel.")
                .ambiguityQuestions("Q1: Is the flag too deep (>50% retrace)? Q2: Is the pole strong enough (5%+ move)?")
                .crossVerificationRules("CV-1: Prior trend must be bullish. CV-2: Pole should be impulsive (not choppy). CV-3: Flag volume must decline.")
                .build();
    }

    private CopilotSkill buildBearFlagSkill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Bear Flag")
                .description("Bear flag — sharp decline (pole) followed by shallow upward-sloping consolidation")
                .category("PATTERN")
                .skillKey("bear_flag")
                .isSystemSeed(true)
                .identificationRules("""
                    A strong impulsive decline forms the "pole" (typically 5-20% drop).
                    Followed by a shallow, upward-sloping consolidation channel — the "flag".
                    Flag retraces 20-50% of the pole (shallow).
                    Volume declines during flag formation.
                    Continuation pattern — expects another decline equal to the pole.
                    """)
                .stageDetection("""
                    - POLE_COMPLETE: Sharp decline done, consolidation beginning
                    - FLAG_FORMING: Shallow rally in progress
                    - BREAKDOWN_PENDING: Flag mature, price near lower flag boundary
                    - CONFIRMED: Breakdown below lower flag trendline
                    """)
                .entryRules("""
                    ANTICIPATORY: Short at upper flag boundary.
                    CONFIRMATION: Short on breakdown below flag, TP = pole height projected down.
                    """)
                .indicatorRules("Volume: Decline in flag. MACD: Remains bearish. RSI: Below 60.")
                .invalidationRules("INVALIDATED if flag retraces more than 50% of pole or breaks above flag channel.")
                .ambiguityQuestions("Q1: Is the flag too steep? Q2: Is the pole impulsive enough?")
                .crossVerificationRules("CV-1: Prior trend must be bearish. CV-2: Pole should be impulsive. CV-3: Flag volume must decline.")
                .build();
    }

    private CopilotSkill buildAscendingChannelSkill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Ascending Channel")
                .description("Ascending channel — parallel rising support and resistance trendlines")
                .category("PATTERN")
                .skillKey("ascending_channel")
                .isSystemSeed(true)
                .identificationRules("""
                    Two parallel or near-parallel upward-sloping trendlines.
                    Price bounces between the channel support (lower) and resistance (upper).
                    Higher highs AND higher lows within the channel.
                    Minimum 2 touches on each trendline (4 total).
                    Trend continuation until channel breaks.
                    A break below channel support signals potential reversal.
                    A break above channel resistance signals acceleration.
                    """)
                .stageDetection("""
                    - FORMING: Channel identified with 2 touches each side
                    - TRENDING: Price respecting both boundaries, trading within channel
                    - UPPER_TEST: Price at or near upper channel boundary
                    - LOWER_TEST: Price at or near lower channel boundary — potential long
                    - CHANNEL_BREAK: Price broke below support or above resistance
                    """)
                .entryRules("""
                    WITHIN CHANNEL: Long at lower boundary, short at upper boundary.
                    BREAKOUT: If price breaks below support, short with TP = channel width projected down.
                    If price breaks above resistance, long with TP = channel width projected up.
                    """)
                .indicatorRules("RSI: Should oscillate between 40-70 within channel. Divergence at highs warns of break. Volume: Declining volume on rallies warns of weakening.")
                .invalidationRules("INVALIDATED as a trend pattern if price closes below lower trendline with conviction.")
                .ambiguityQuestions("Q1: Are the trendlines truly parallel? Q2: Is the channel mature enough for reliable boundary trades?")
                .crossVerificationRules("CV-1: Higher TF trend should support the channel direction. CV-2: Channel boundary trades need lower TF confirmation.")
                .build();
    }

    private CopilotSkill buildDescendingChannelSkill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Descending Channel")
                .description("Descending channel — parallel declining support and resistance trendlines")
                .category("PATTERN")
                .skillKey("descending_channel")
                .isSystemSeed(true)
                .identificationRules("""
                    Two parallel or near-parallel downward-sloping trendlines.
                    Lower highs AND lower lows within the channel.
                    Minimum 2 touches on each trendline.
                    A break above channel resistance signals potential reversal.
                    A break below channel support signals acceleration down.
                    """)
                .stageDetection("""
                    - FORMING: Channel identified
                    - TRENDING: Price respecting both boundaries
                    - UPPER_TEST: Price at upper boundary — potential short
                    - LOWER_TEST: Price at lower boundary
                    - CHANNEL_BREAK: Price broke above resistance or below support
                    """)
                .entryRules("""
                    WITHIN CHANNEL: Short at upper boundary, long at lower boundary.
                    BREAKOUT: If price breaks above resistance, long with measured move target.
                    """)
                .indicatorRules("RSI: Should oscillate 30-60. Bullish divergence at lows warns of reversal. Volume: Expanding on declines supports continuation.")
                .invalidationRules("INVALIDATED if price closes above upper trendline with conviction.")
                .ambiguityQuestions("Q1: Is the channel mature? Q2: Is a reversal forming at support?")
                .crossVerificationRules("CV-1: Higher TF trend direction. CV-2: Check for RSI divergence at channel lows.")
                .build();
    }

    private CopilotSkill buildCupAndHandleSkill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Cup and Handle")
                .description("Cup and handle — rounded bottom (cup) with small consolidation (handle), bullish")
                .category("PATTERN")
                .skillKey("cup_and_handle")
                .isSystemSeed(true)
                .identificationRules("""
                    A U-shaped (rounded) bottom forming the "cup" over several weeks/months.
                    Cup depth: typically 12-35% from prior high to cup bottom.
                    After the cup completes, a small downward drift or consolidation forms the "handle".
                    Handle should retrace 10-50% of the cup depth (shallow is better).
                    Handle should form in the upper 1/3 of the cup.
                    Breakout above the cup rim (prior high/resistance) confirms the pattern.
                    Volume: declines during cup formation, picks up in handle, expands on breakout.
                    """)
                .stageDetection("""
                    - CUP_FORMING: Rounded decline and recovery in progress
                    - CUP_COMPLETE: Price returned to prior high area (cup rim)
                    - HANDLE_FORMING: Small pullback/consolidation from cup rim
                    - BREAKOUT_PENDING: Handle complete, price approaching cup rim
                    - CONFIRMED: Breakout above cup rim with volume
                    """)
                .entryRules("""
                    ANTICIPATORY: Long at handle support with SL below handle low.
                    CONFIRMATION: Long on breakout above cup rim.
                    TP = cup depth projected from rim breakout point.
                    """)
                .indicatorRules("Volume: U-shaped volume (high-low-high). RSI: Should show strength above 50 as cup completes. Handle volume should be light.")
                .invalidationRules("INVALIDATED if handle retraces >50% of cup, or cup is V-shaped (too sharp).")
                .ambiguityQuestions("Q1: Is the cup rounded or V-shaped? Q2: Is the handle too deep?")
                .crossVerificationRules("CV-1: Higher TF should show the prior uptrend that created the cup. CV-2: The cup rim should align with a meaningful resistance level.")
                .build();
    }

    private CopilotSkill buildTripleTopSkill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Triple Top")
                .description("Triple top — three peaks at similar level, bearish reversal")
                .category("PATTERN")
                .skillKey("triple_top")
                .isSystemSeed(true)
                .identificationRules("""
                    Three distinct peaks at approximately the same price level (within 1-2%).
                    Two troughs between the peaks form the support/neckline level.
                    Each rally to resistance shows sellers defending the level.
                    Volume typically diminishes on each successive peak.
                    Confirmed when price breaks below the support formed by the two troughs.
                    More reliable than double top as resistance has been tested three times.
                    """)
                .stageDetection("""
                    - FIRST_PEAK: First high formed
                    - SECOND_PEAK: Second test of resistance
                    - THIRD_PEAK: Third test of resistance — pattern developing
                    - BREAKDOWN_PENDING: Third peak failed, declining toward support
                    - CONFIRMED: Price broke below support/neckline
                    """)
                .entryRules("""
                    ANTICIPATORY: Short at resistance on third test with SL above highs.
                    CONFIRMATION: Short on breakdown below support, TP = height of pattern projected down.
                    """)
                .indicatorRules("RSI: Bearish divergence across three peaks. Volume: Diminishing on each peak.")
                .invalidationRules("INVALIDATED if price breaks above resistance on any of the three tests with strong volume.")
                .ambiguityQuestions("Q1: Is this a triple top or a consolidation range? Q2: Is resistance truly holding?")
                .crossVerificationRules("CV-1: Higher TF must show mature uptrend. CV-2: Three tests of resistance is strong evidence of exhaustion.")
                .build();
    }

    private CopilotSkill buildTripleBottomSkill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Triple Bottom")
                .description("Triple bottom — three troughs at similar level, bullish reversal")
                .category("PATTERN")
                .skillKey("triple_bottom")
                .isSystemSeed(true)
                .identificationRules("""
                    Three distinct troughs at approximately the same price level (within 1-2%).
                    Two rallies between the troughs form the resistance/neckline level.
                    Each test of support shows buyers defending the level.
                    Volume often increases on the third test and breakout.
                    Confirmed when price breaks above the resistance/neckline.
                    """)
                .stageDetection("""
                    - FIRST_TROUGH: First low formed
                    - SECOND_TROUGH: Second test of support
                    - THIRD_TROUGH: Third test — pattern developing
                    - BREAKOUT_PENDING: Third trough held, rallying toward resistance
                    - CONFIRMED: Price broke above resistance/neckline
                    """)
                .entryRules("""
                    ANTICIPATORY: Long at support on third test with SL below lows.
                    CONFIRMATION: Long on breakout above resistance, TP = height of pattern projected up.
                    """)
                .indicatorRules("RSI: Bullish divergence across three troughs. Volume: Should increase on third test and breakout.")
                .invalidationRules("INVALIDATED if price breaks below support level on any test with strong volume.")
                .ambiguityQuestions("Q1: Is this a triple bottom or a range? Q2: Is support truly holding?")
                .crossVerificationRules("CV-1: Higher TF must show mature downtrend. CV-2: Three tests of support is strong evidence of accumulation.")
                .build();
    }

    private CopilotSkill buildRoundingBottomSkill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Rounding Bottom")
                .description("Rounding bottom (saucer) — gradual U-shaped reversal, bullish")
                .category("PATTERN")
                .skillKey("rounding_bottom")
                .isSystemSeed(true)
                .identificationRules("""
                    A gradual, U-shaped price pattern over an extended period.
                    No sharp V-bottom — the transition from decline to rally is smooth and rounded.
                    Price slowly transitions from lower lows to higher lows.
                    The decline, bottom, and recovery should be roughly symmetrical in time.
                    Volume follows a similar U-shape: high during decline, low at bottom, rising on recovery.
                    Breakout confirmed when price exceeds the left rim of the pattern.
                    """)
                .stageDetection("""
                    - DECLINE_PHASE: Left side of the saucer forming, price declining
                    - BOTTOM_FORMING: Gradual flattening, momentum shift
                    - RECOVERY_PHASE: Right side rising, price making higher lows
                    - BREAKOUT_PENDING: Price approaching the rim level
                    - CONFIRMED: Price exceeds the left rim
                    """)
                .entryRules("""
                    ANTICIPATORY: Long in recovery phase as higher lows form.
                    CONFIRMATION: Long on breakout above the rim.
                    """)
                .indicatorRules("Volume: U-shaped (mirrors price). RSI: Should transition from oversold toward bullish. MACD: Histogram should transition from bearish to bullish during pattern.")
                .invalidationRules("INVALIDATED if price makes a sharp V-bottom (not gradual) or makes new lows after recovery begins.")
                .ambiguityQuestions("Q1: Is the bottom truly rounded or V-shaped? Q2: Is the recovery phase genuine or a dead cat bounce?")
                .crossVerificationRules("CV-1: Pattern takes significant time to form. CV-2: Volume confirmation is essential. CV-3: Higher TF should show the end of a major downtrend.")
                .build();
    }

    private CopilotSkill buildBroadeningSKill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Broadening Pattern")
                .description("Broadening formation (megaphone) — expanding highs and lows, high volatility")
                .category("PATTERN")
                .skillKey("broadening_pattern")
                .isSystemSeed(true)
                .identificationRules("""
                    Diverging trendlines — higher highs AND lower lows (opposite of triangle).
                    Creates a megaphone or expanding shape.
                    Each swing is larger than the previous — increasing volatility.
                    Minimum 2 touches on each diverging trendline.
                    Often appears at market tops after extended rallies (bearish implications).
                    Can also appear mid-trend. Resolution direction is uncertain.
                    Very difficult to trade — high volatility and false breakouts common.
                    """)
                .stageDetection("""
                    - FORMING: First expansion visible, 2 touches on each diverging line
                    - MATURE: Multiple swings with expanding amplitude
                    - RESOLUTION_PENDING: Price approaching one of the diverging boundaries
                    - CONFIRMED: Decisive break beyond pattern boundary that holds
                    """)
                .entryRules("""
                    COUNTER-TREND: Fade at trendline boundaries (risky, tight stops required).
                    BREAKOUT: Wait for decisive break and hold beyond the pattern, trade in that direction.
                    This pattern is best avoided unless experienced with high volatility setups.
                    """)
                .indicatorRules("Volume: Typically increases as pattern progresses. RSI: Wide swings between overbought and oversold. MACD: Alternating strong bullish and bearish signals.")
                .invalidationRules("INVALIDATED if the swings stop expanding — may be transitioning to a range or channel.")
                .ambiguityQuestions("Q1: Is this broadening or just choppy? Q2: Is the pattern at a market top?")
                .crossVerificationRules("CV-1: Often appears after a strong trend — check if prior trend was extended. CV-2: These patterns have low reliability — proceed with caution. CV-3: Breakout must be confirmed with volume and hold.")
                .build();
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

    // ═══════════════════════════════════════════════════════════════════════════
    // WAVE SKILLS — Elliott Wave structure detection with pattern/indicator
    //                correlation and trading opportunity mapping
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Seed all wave skills for a user.
     * Skips any skill whose key already exists for this user.
     */
    @Transactional
    public int seedWaveSkillsForUser(Long userId) {
        int count = 0;
        for (CopilotSkill skill : buildAllWaveSkills(userId)) {
            if (skillRepository.findByUserIdAndSkillKey(userId, skill.getSkillKey()).isEmpty()) {
                skillRepository.save(skill);
                count++;
            }
        }
        return count;
    }

    private List<CopilotSkill> buildAllWaveSkills(Long userId) {
        return List.of(
            buildWave1Skill(userId),
            buildWave2Skill(userId),
            buildWave3Skill(userId),
            buildWave4SkillEnriched(userId),
            buildWave5Skill(userId),
            buildWaveASkill(userId),
            buildWaveBSkill(userId),
            buildWaveCSkill(userId),
            buildLeadingDiagonalSkill(userId),
            buildEndingDiagonalSkill(userId),
            buildExtendedWave3Skill(userId),
            buildFlatCorrectionSkill(userId),
            buildZigzagCorrectionSkill(userId),
            buildComplexCorrectionWXYSkill(userId),
            buildWaveTriangleSkill(userId)
        );
    }

    // ─── Wave 1: Impulse Start ─────────────────────────────────────────────────

    private CopilotSkill buildWave1Skill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Wave 1 — Impulse Start")
                .description("Elliott Wave 1 — first impulse leg after trend reversal, often mistaken for a correction")
                .category("WAVE")
                .skillKey("wave_1")
                .isSystemSeed(true)
                .identificationRules("""
                    Wave 1 is the FIRST impulse leg of a new trend direction.
                    It emerges after the completion of a prior corrective structure (Wave C or Wave 5 exhaustion).
                    Wave 1 must show 5-wave sub-structure on lower timeframe (impulse, not corrective).
                    It is typically modest in size and often mistaken for a bear market rally or continuation pullback.
                    Volume usually increases as Wave 1 progresses but may not be dramatic.
                    Wave 1 breaks the prior downtrend line or channel in a bullish case (vice versa for bearish).

                    === CHART PATTERN CORRELATION ===
                    - Double bottom / inverse H&S at Wave 1 origin = strong confirmation of trend reversal
                    - Rounding bottom completing as Wave 1 launches
                    - Falling wedge breakout often marks Wave 1 start (wedge = prior Wave C or Wave 5)
                    - Failed breakdown / spring pattern at Wave 1 origin

                    === CANDLE PATTERN CORRELATION ===
                    - Bullish engulfing / morning star at Wave 1 origin on higher TF
                    - Hammer / dragonfly doji at the absolute low
                    - Three white soldiers as Wave 1 accelerates
                    - Piercing line on weekly = early Wave 1 signal

                    === INDICATOR CORRELATION ===
                    - RSI: Rising from oversold (<30), positive divergence vs prior low
                    - MACD: Histogram turning positive after prolonged bearish readings, bullish crossover
                    - Volume: Increasing as price rises, declining on pullbacks
                    - Stochastic: Crossing up from oversold, staying elevated
                    - ADX: Rising from low levels (<20) = new trend emerging
                    """)
                .stageDetection("""
                    - EARLY_WAVE_1: Price just broke prior downtrend line, sub-wave i and ii of Wave 1 forming
                    - MID_WAVE_1: Sub-wave iii developing — strongest part of Wave 1, volume increasing
                    - LATE_WAVE_1: Sub-wave iv-v completing, momentum may be waning
                    - WAVE_1_COMPLETE: 5-wave structure visible on lower TF, price has established a higher low + higher high
                    """)
                .entryRules("""
                    === TRADING OPPORTUNITY: Enter Wave 1 early (catching reversal) ===
                    ANTICIPATORY ENTRY (at reversal point — highest risk/reward):
                    - Entry zone: at completion of prior Wave C/5, near double bottom / reversal pattern
                    - SL: below the absolute low of prior Wave C/5 (1-2% buffer)
                    - TP1: prior Wave 4 territory (first resistance zone)
                    - TP2: 1.0x–1.618x extension of Wave 1 sub-wave i from sub-wave ii end
                    - Requires: bullish reversal candle + positive RSI divergence + volume expansion

                    === TRADING OPPORTUNITY: Enter pullback within Wave 1 ===
                    CONFIRMATION ENTRY (on sub-wave ii or iv pullback):
                    - Entry: at 50%–61.8% retracement of the prior impulse sub-wave
                    - SL: below the prior sub-wave low
                    - TP: extension target for the next sub-wave
                    - Requires: 3-wave corrective pullback on lower TF, support at Fibonacci level
                    """)
                .indicatorRules("""
                    RSI: Must show positive divergence between prior Wave C low and Wave 1 origin.
                    RSI should not exceed 70 during Wave 1 (that's more typical of Wave 3).
                    If RSI already >70 in Wave 1 → Flag: 'Unusually strong for Wave 1, could be Wave 3 at higher degree.'

                    MACD: Histogram should turn positive. If MACD was deeply negative, the zero-line cross
                    often occurs in Wave 1 or early Wave 2. Bullish crossover confirms direction.

                    Volume: Should expand during Wave 1 rises, contract on pullbacks.
                    If volume is absent → Flag: 'Low volume Wave 1 — may lack follow-through.'

                    Stochastic: Rising from oversold confirms momentum shift.
                    """)
                .invalidationRules("""
                    INVALIDATED if:
                    - Price falls below the Wave 1 origin (new low) — prior trend not reversed
                    - Sub-structure shows only 3 waves (corrective, not impulse) — this is a bear market rally, not Wave 1
                    - MACD fails to cross above zero or immediately rolls over — no momentum shift
                    - Volume declines throughout the entire move — no institutional participation
                    - Prior trend line is recaptured downward after brief breakout (false breakout)
                    """)
                .ambiguityQuestions("""
                    Q1: Is this truly Wave 1 of a new trend or sub-wave C of a larger correction?
                    (Check higher TF — is the prior structure a complete 5-wave down or just 3-wave?)
                    Q2: The move has 5 waves but is it a leading diagonal (Wave 1) or an impulse (Wave 3)?
                    Check proportions — leading diagonal has overlapping waves, impulse does not.
                    Q3: Volume is ambiguous — is this institutional accumulation or a dead cat bounce?
                    """)
                .crossVerificationRules("""
                    CV-1: Prior wave structure must be complete (5-wave decline or ABC correction ended).
                    If prior structure only shows 3 waves down → Flag: 'Prior decline may not be complete.'

                    CV-2: Wave 1 must show 5-wave impulse sub-structure on lower TF.
                    If only 3 waves visible → Flag: 'Possible corrective rally, not Wave 1.'

                    CV-3: Check for chart pattern confirmation at origin — double bottom, inv H&S, falling wedge breakout.
                    Absence of reversal pattern → lower confidence.

                    CV-4: Higher TF trend context — is this a counter-trend Wave 1 or a trend-resumption Wave 1?
                    If weekly trend is still strongly bearish → Flag: 'Counter-trend Wave 1 — lower probability.'

                    CV-5: RSI divergence must be present between prior low and Wave 1 origin.
                    No divergence → Flag: 'Missing RSI divergence — Wave 1 identification weakened.'
                    """)
                .build();
    }

    // ─── Wave 2: First Correction ──────────────────────────────────────────────

    private CopilotSkill buildWave2Skill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Wave 2 — First Correction")
                .description("Elliott Wave 2 — deep correction of Wave 1, sets up the powerful Wave 3 entry")
                .category("WAVE")
                .skillKey("wave_2")
                .isSystemSeed(true)
                .identificationRules("""
                    Wave 2 corrects Wave 1 and CANNOT retrace beyond the start of Wave 1 (absolute rule).
                    Wave 2 typically retraces 50%–78.6% of Wave 1 (deep correction).
                    Wave 2 is usually a SHARP correction — zigzag (5-3-5) is the most common form.
                    Wave 2 must show 3-wave corrective sub-structure (ABC) on lower TF.
                    Wave 2 often retraces so deeply that traders abandon the new trend thesis.

                    === CHART PATTERN CORRELATION ===
                    - Bull flag / bear flag forming during Wave 2 (pullback flag)
                    - Falling wedge within Wave 2 = bullish (Wave 2 ending, Wave 3 about to start)
                    - Double bottom at Wave 2 termination = strong entry signal for Wave 3
                    - Inverse H&S forming at Wave 2 end on lower TF
                    - Channel — Wave 2 often respects a corrective channel (A and C end at channel boundary)

                    === CANDLE PATTERN CORRELATION ===
                    - Hammer / bullish engulfing at Wave 2 end near 61.8% retracement
                    - Morning star at Fibonacci support level
                    - Doji / spinning top showing indecision before Wave 3 launch
                    - Bearish candles dominate Wave 2 body — then sudden reversal candle at end

                    === INDICATOR CORRELATION ===
                    - RSI: Falls but HOLDS above 30 (or shows divergence vs Wave 1 origin low)
                    - MACD: Pulls back toward zero line but does NOT cross below (bullish case)
                    - Stochastic: Reaches oversold and crosses up = Wave 3 trigger
                    - Volume: Declining throughout Wave 2 (selling drying up)
                    - Fibonacci: 50%, 61.8%, or 78.6% retracement of Wave 1 as support
                    """)
                .stageDetection("""
                    - WAVE_2_A: First corrective leg down — sharp decline (impulse sub-structure)
                    - WAVE_2_B: Counter-rally within correction — partially retraces leg A
                    - WAVE_2_C: Final leg down — approaching Fibonacci support of Wave 1
                    - WAVE_2_COMPLETE: Price has held above Wave 1 origin, corrective structure complete
                    - WAVE_3_LAUNCHING: Early Wave 3 impulse visible on lower TF from Wave 2 end
                    """)
                .entryRules("""
                    === TRADING OPPORTUNITY: Wave 3 entry at Wave 2 completion (BEST R:R in Elliott) ===
                    ANTICIPATORY ENTRY (at Wave 2 end zone — catching Wave 3 early):
                    - Entry zone: 50%–78.6% retracement of Wave 1, at Fibonacci cluster
                    - SL: below Wave 1 origin (MUST NOT break — absolute rule)
                    - TP1: Wave 3 target = 1.618x extension of Wave 1 from Wave 2 end
                    - TP2: Wave 3 target = 2.618x extension of Wave 1 (if extended Wave 3)
                    - TP3: Wave 5 eventual target = beyond Wave 3 high
                    - Requires: reversal candle at Fib level + declining volume + stochastic oversold crossup
                    - R:R ratio: typically 3:1 to 8:1 (one of the best setups in trading)

                    CONFIRMATION ENTRY (early Wave 3 breakout):
                    - Entry: when price breaks above Wave 2-B high with impulse momentum
                    - SL: below Wave 2 low
                    - TP: 1.618x extension target
                    - Requires: volume expansion + MACD bullish crossover + 5-wave impulse starting on lower TF
                    """)
                .indicatorRules("""
                    RSI: Should pull back during Wave 2 but remain above 30 (bullish) or 70 (bearish continuation).
                    RSI divergence between Wave 1 start and Wave 2 end = powerful Wave 3 signal.

                    MACD: Should compress during Wave 2. MACD crossing back below zero during Wave 2
                    is acceptable but concerning — monitor closely.
                    If MACD makes a new extreme during Wave 2 → Flag: 'Wave 2 too impulsive — may not be corrective.'

                    Volume: MUST decline during Wave 2. If volume expands → Flag: 'Expanding volume in Wave 2 — possible trend continuation, not correction.'

                    Stochastic: Oversold crossup at Wave 2 end = timing signal for Wave 3 entry.

                    Fibonacci: Wave 2 ending at 61.8% retracement = most common and highest probability.
                    """)
                .invalidationRules("""
                    INVALIDATED if:
                    - Price retraces beyond 100% of Wave 1 (Wave 2 CANNOT break Wave 1 origin — absolute rule)
                    - Wave 2 shows 5-wave impulse sub-structure (must be 3-wave corrective)
                    - Volume expands significantly during Wave 2 (should be declining)
                    - MACD makes a new extreme beyond what was seen before Wave 1 started
                    - Wave 2 takes longer than 2x the time of Wave 1 (time proportion violation)
                    """)
                .ambiguityQuestions("""
                    Q1: Is this Wave 2 or a continuation of the prior trend? The retracement is 78% — very deep.
                    Q2: Sub-structure shows possible 5-wave decline — is this a new impulse down or a sharp zigzag correction?
                    Q3: The correction seems to be forming a flat (3-3-5) instead of zigzag — is this still valid as Wave 2?
                    (Flats are less common in Wave 2, more common in Wave 4)
                    Q4: Wave 2 is taking very long — is this morphing into a larger corrective structure?
                    """)
                .crossVerificationRules("""
                    CV-1: Wave 1 must be confirmed as 5-wave impulse before accepting Wave 2.
                    If Wave 1 was only 3 waves → Flag: 'Wave 1 may be incomplete.'

                    CV-2: Wave 2 must hold above Wave 1 origin. Check intraday — even an intraday breach is concerning.

                    CV-3: Fibonacci retracement — Wave 2 should end at 50%, 61.8%, or 78.6% of Wave 1.
                    Random endpoint → Flag: 'No Fibonacci support at Wave 2 end.'

                    CV-4: Check for reversal chart pattern forming at Wave 2 end (double bottom, inv H&S, falling wedge).
                    Pattern present → higher confidence. No pattern → lower confidence.

                    CV-5: Higher TF context — Wave 2 should be within a larger bullish structure.
                    If weekly is still bearish → Flag: 'Counter-trend — Wave 2 end may not hold.'
                    """)
                .build();
    }

    // ─── Wave 3: Strongest Impulse ─────────────────────────────────────────────

    private CopilotSkill buildWave3Skill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Wave 3 — Strongest Impulse")
                .description("Elliott Wave 3 — longest/strongest wave, never the shortest, typically 1.618x Wave 1")
                .category("WAVE")
                .skillKey("wave_3")
                .isSystemSeed(true)
                .identificationRules("""
                    Wave 3 is the most powerful and typically LONGEST wave in the impulse sequence.
                    ABSOLUTE RULE: Wave 3 can NEVER be the shortest of Waves 1, 3, and 5.
                    Wave 3 usually extends to 1.618x of Wave 1 (measured from Wave 2 end).
                    Extended Wave 3 can reach 2.618x or even 4.236x of Wave 1.
                    Wave 3 must show 5-wave impulse sub-structure on lower TF.
                    Volume is highest and most consistent in Wave 3.
                    Gaps (breakaway gaps) are common in Wave 3 — they show conviction.

                    === CHART PATTERN CORRELATION ===
                    - Breakout from inverse H&S or double bottom often IS the start of Wave 3
                    - Bull flag breakout at Wave 2 end launches Wave 3
                    - Ascending triangle breakout mid-Wave 3 (sub-wave iv consolidation)
                    - Channel breakout — Wave 3 often breaks the Wave 1-2 channel to the upside
                    - No topping patterns should appear during Wave 3 (if they do, question the count)

                    === CANDLE PATTERN CORRELATION ===
                    - Strong marubozu / long bullish candles dominate Wave 3
                    - Breakaway gaps early in Wave 3
                    - Continuation gaps (runaway gaps) mid-Wave 3
                    - Three white soldiers pattern recurring
                    - Absence of upper wicks on daily candles = strong trend

                    === INDICATOR CORRELATION ===
                    - RSI: Above 50 for entire Wave 3, often reaching 70-80+ (overbought is normal)
                    - MACD: Making new highs, histogram expanding — strongest readings of the entire move
                    - Volume: Highest and most consistent — each rally leg on increasing volume
                    - ADX: Rising above 25-30, confirming strong trend
                    - Bollinger Bands: Price riding the upper band (bullish) — "walking the bands"
                    """)
                .stageDetection("""
                    - EARLY_WAVE_3: Sub-waves i-ii forming, breakout from Wave 2 pattern
                    - MID_WAVE_3: Sub-wave iii (the "heart" of Wave 3) — strongest momentum, gaps likely
                    - EXTENDED_WAVE_3: Wave 3 extending beyond 1.618x — reaching 2.618x or more
                    - LATE_WAVE_3: Sub-waves iv-v — momentum still positive but rate of change slowing
                    - WAVE_3_COMPLETE: 5-wave sub-structure complete, MACD starting to compress
                    """)
                .entryRules("""
                    === TRADING OPPORTUNITY: Ride Wave 3 (mid-wave entry) ===
                    ENTRY ON SUB-WAVE ii or iv PULLBACK:
                    - Entry zone: 38.2%–50% retracement of prior sub-wave (Wave 3 pullbacks are shallow)
                    - SL: below prior sub-wave low
                    - TP: next sub-wave extension (1.618x of prior leg)
                    - Requires: pullback on declining volume, support at Fibonacci level

                    === TRADING OPPORTUNITY: Wave 3 breakout continuation ===
                    BREAKOUT ENTRY (channel/pattern breakout mid-Wave 3):
                    - Entry: on breakout above Wave 1 high (confirms Wave 3 underway)
                    - SL: below Wave 2 low (wide but safe)
                    - TP1: 1.618x extension of Wave 1
                    - TP2: 2.618x if extended
                    - Requires: volume expansion + RSI >50 + MACD expanding

                    === TRADING OPPORTUNITY: DO NOT SHORT Wave 3 ===
                    WARNING: Shorting into Wave 3 is the most dangerous trade. Wave 3 traps bears.
                    Any short signal during confirmed Wave 3 should be ignored.
                    """)
                .indicatorRules("""
                    RSI: Will be elevated (60-80) throughout Wave 3. Do NOT use RSI "overbought" as a sell signal
                    during Wave 3 — RSI can stay overbought for the entire wave. RSI below 50 during supposed
                    Wave 3 → Flag: 'RSI too weak for Wave 3 — check wave count.'

                    MACD: Must make new highs during Wave 3. Histogram should expand.
                    If MACD is compressing during supposed Wave 3 → Flag: 'MACD not expanding — may not be Wave 3.'
                    MACD bearish divergence between sub-wave iii and v = Wave 3 nearing completion.

                    Volume: Highest volume of the entire impulse sequence. Each sub-wave advance on increasing volume.
                    If volume is anemic → Flag: 'Weak volume for Wave 3 — question the count.'

                    ADX: Rising above 25 confirms trending condition. ADX declining → not Wave 3.
                    """)
                .invalidationRules("""
                    INVALIDATED if:
                    - Wave 3 is shorter than BOTH Wave 1 and Wave 5 (Wave 3 can NEVER be shortest — absolute rule)
                    - MACD fails to make a new high above Wave 1's MACD reading
                    - Volume is lower than Wave 1 volume
                    - RSI stays below 50 throughout (no momentum)
                    - Sub-structure shows only 3 waves (corrective structure — not an impulse)
                    - Price fails to exceed Wave 1 high (Wave 3 must go beyond Wave 1 end)
                    """)
                .ambiguityQuestions("""
                    Q1: Is this Wave 3 or an extended Wave 1? Check — if this is only the first impulse leg and
                    we haven't seen a proper Wave 2 yet, this could still be Wave 1.
                    Q2: The wave has already reached 1.618x — is Wave 3 complete or extending to 2.618x?
                    Check lower TF sub-structure for completion signals.
                    Q3: I see a possible sub-wave iv triangle — is Wave 3 about to end or does it have one more push?
                    Q4: MACD is showing divergence — is this Wave 3 completing or Wave 5 at a higher degree?
                    """)
                .crossVerificationRules("""
                    CV-1: Wave 1 and Wave 2 must be confirmed before labeling Wave 3.
                    Wave 1 = 5-wave impulse, Wave 2 = 3-wave correction holding above Wave 1 origin.

                    CV-2: Wave 3 must show 5-wave impulse sub-structure on lower TF.
                    Only 3 waves visible → Flag: 'Insufficient sub-structure for Wave 3.'

                    CV-3: MACD must make new highs vs Wave 1. This is a key differentiator.
                    MACD lower than Wave 1 → Flag: 'Momentum not confirming Wave 3.'

                    CV-4: Volume must be the strongest of the move.
                    Lower volume than Wave 1 → Flag: 'Volume doesn't support Wave 3 identification.'

                    CV-5: Wave 3 should correlate with a chart pattern breakout (inv H&S, double bottom, flag).
                    No pattern confirmation → lower confidence.

                    CV-6: Higher TF context — Wave 3 should align with the higher TF trend direction.
                    Counter-trend Wave 3 → Flag: 'Counter-trend — reduced targets.'
                    """)
                .build();
    }

    // ─── Wave 4: Corrective Phase (ENRICHED version) ───────────────────────────

    private CopilotSkill buildWave4SkillEnriched(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Wave 4 — Corrective Phase")
                .description("Elliott Wave 4 — corrective phase after Wave 3, sets up Wave 5 entry, rich pattern correlation")
                .category("WAVE")
                .skillKey("wave_4_enriched")
                .isSystemSeed(true)
                .identificationRules("""
                    Wave 4 corrects Wave 3 and CANNOT overlap Wave 1 price territory (non-overlap rule).
                    Exception: ending diagonals in Wave 5 or Wave C only.
                    Wave 4 typically retraces 38.2%–50% of Wave 3 (shallower than Wave 2).
                    GUIDELINE OF ALTERNATION: If Wave 2 was sharp (zigzag), Wave 4 tends to be sideways (flat/triangle).
                    If Wave 2 was sideways, Wave 4 tends to be sharp.
                    Wave 4 is often complex, time-consuming, and the most frustrating wave to trade.
                    FORBIDDEN: Wave 4 cannot be a simple 5-wave impulse — it must be corrective (3-wave).

                    === CHART PATTERN CORRELATION ===
                    - Symmetrical triangle = most common Wave 4 pattern (contracting range before Wave 5)
                    - Ascending triangle (bullish bias) in Wave 4 → strong Wave 5 expected
                    - Bull flag / pennant = Wave 4 in a strong uptrend
                    - Rising wedge in Wave 4 → bearish warning (Wave 5 may be truncated)
                    - Channel — Wave 4 often respects the 0-2 trend channel
                    - Rectangle / range-bound trading = flat correction pattern

                    === CANDLE PATTERN CORRELATION ===
                    - Small-bodied candles, dojis, spinning tops dominate Wave 4 (indecision)
                    - Inside bars / harami patterns = consolidation
                    - Hammer at Wave 4 termination before Wave 5 launch
                    - Evening star / shooting star ABSENT (those appear at Wave 5 end, not Wave 4)
                    - Lack of conviction candles = classic Wave 4 personality

                    === INDICATOR CORRELATION ===
                    - RSI: Pulls back to 40-50 zone but holds above 30 (in uptrend)
                    - MACD: COMPRESSING — histogram declining, signal lines converging
                    - Volume: Declining throughout Wave 4 (consolidation, drying up)
                    - Bollinger Bands: Contracting (squeeze) before Wave 5 expansion
                    - Stochastic: Oscillating, reaching oversold = timing signal for Wave 5 entry
                    - ADX: Declining — trend pausing, not reversing
                    """)
                .stageDetection("""
                    - WAVE_4_STARTING: First pullback from Wave 3 high, uncertain if Wave 3 is truly done
                    - WAVE_4_A: First corrective leg — could be sharp (zigzag) or flat
                    - WAVE_4_B: Counter-move within correction
                    - WAVE_4_C_OR_D: Later legs forming — if triangle, legs D and E pending
                    - WAVE_4_TRIANGLE: Contracting triangle identified — counting ABCDE legs
                    - WAVE_4_COMPLETE: Corrective structure done, non-overlap rule held, Wave 5 about to begin
                    """)
                .entryRules("""
                    === TRADING OPPORTUNITY: Wave 5 entry at Wave 4 completion (high probability) ===
                    ANTICIPATORY ENTRY (at Wave 4 end zone):
                    - Entry zone: 38.2%–50% retracement of Wave 3, at triangle/flag/flat completion
                    - SL: below Wave 4 low (or Wave 1 high — non-overlap level)
                    - TP1: Wave 5 = 0.618x–1.0x of Wave 1 (most common)
                    - TP2: Wave 5 = 1.0x of Wave 1 (equality guideline)
                    - TP3: 0.382x of Wave 1-3 net distance added to Wave 4 end
                    - Requires: corrective structure complete + Bollinger squeeze + stochastic oversold crossup
                    - R:R ratio: typically 2:1 to 5:1

                    CONFIRMATION ENTRY (Wave 5 breakout):
                    - Entry: when price breaks above the triangle/flag/correction boundary
                    - SL: below Wave 4 low
                    - TP: Wave 5 extension targets
                    - Requires: volume expansion on breakout + MACD bullish crossover from compressed state

                    === TRADING OPPORTUNITY: Fade Wave 4 extremes (advanced) ===
                    MEAN REVERSION within Wave 4:
                    - Entry: at boundaries of the corrective pattern (triangle edges, channel extremes)
                    - SL: just outside pattern boundary
                    - TP: opposite side of pattern
                    - Very short-term, requires precise timing
                    """)
                .indicatorRules("""
                    MACD: MUST be compressing during Wave 4. Lower histogram highs, signal lines converging.
                    If MACD makes a new high during Wave 4 → Flag: 'MACD expansion contradicts Wave 4 — Wave 3 may be extending.'

                    RSI: Should decline/consolidate. RSI should not make new highs.
                    RSI holding above 40 = bullish context maintained.
                    RSI falling below 30 → Flag: 'RSI too weak for Wave 4 — possible trend reversal, not correction.'

                    Stochastic: At Wave 4 end — should reach oversold before Wave 5 begins.
                    Stochastic crossover from oversold zone = lower TF entry trigger for Wave 5.

                    Bollinger Bands: Width contracting = squeeze building. Breakout from squeeze = Wave 5 start.

                    Volume: Declining throughout. Volume spike = Wave 4 may be ending (capitulation).
                    """)
                .invalidationRules("""
                    INVALIDATED if:
                    - Price closes below Wave 1 high (non-overlap rule violated)
                      Exception: only if ending diagonal structure is confirmed
                    - Wave 4 retracement exceeds 78.6% of Wave 3 (too deep — likely not Wave 4)
                    - Wave 4 shows 5-wave impulse sub-structure (must be corrective)
                    - MACD makes a new high during Wave 4 (suggests Wave 3 extending)
                    - Wave 4 takes longer than 3x the time of Wave 3 (excessive time)
                    - Volume EXPANDS during Wave 4 with momentum (suggests new impulse, not correction)
                    """)
                .ambiguityQuestions("""
                    Q1: Is Wave 3 truly complete? Could this be a sub-wave iv within an extending Wave 3?
                    Q2: Retracement is 55% — deeper than typical Wave 4 (38.2%). Is this still valid?
                    Q3: The correction looks like it could be either a flat or a triangle — which is it?
                    Q4: Non-overlap rule — Wave 4 low is very close to Wave 1 high. Is it holding?
                    Q5: Alternation check — Wave 2 was a zigzag. Is Wave 4 showing alternation (flat/triangle)?
                    """)
                .crossVerificationRules("""
                    CV-1: Verify Wave 3 completion — 5-wave sub-structure must be visible on lower TF.
                    Incomplete Wave 3 → Flag: 'Wave 3 may still be extending.'

                    CV-2: Non-overlap rule — check that Wave 4 has NOT entered Wave 1 territory.
                    Overlap → Flag: 'Non-overlap violated. Ending diagonal or wrong count.'

                    CV-3: MACD must be compressing. Expanding MACD → Flag: 'Not Wave 4 behavior.'

                    CV-4: Alternation — Wave 4 should differ from Wave 2 in form.
                    Same form as Wave 2 → Flag: 'Alternation guideline violated — lower confidence.'

                    CV-5: Fibonacci — Wave 4 end should land at 38.2% or 50% retracement of Wave 3.
                    No Fibonacci support → Flag: 'No Fibonacci confluence at Wave 4 end.'

                    CV-6: Chart pattern — triangle, flag, or flat should be identifiable.
                    No clear pattern → Flag: 'Shapeless Wave 4 — hard to time entry.'
                    """)
                .build();
    }

    // ─── Wave 5: Final Impulse / Exhaustion ────────────────────────────────────

    private CopilotSkill buildWave5Skill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Wave 5 — Final Impulse")
                .description("Elliott Wave 5 — last impulse leg, often shows exhaustion, sets up ABC correction entry")
                .category("WAVE")
                .skillKey("wave_5")
                .isSystemSeed(true)
                .identificationRules("""
                    Wave 5 is the FINAL impulse leg in the motive sequence.
                    Wave 5 must show 5-wave sub-structure (or 3-wave if ending diagonal).
                    Wave 5 is typically WEAKER than Wave 3 in momentum and volume.
                    BEARISH DIVERGENCE between Wave 3 and Wave 5 is the hallmark signal.
                    Wave 5 can be TRUNCATED (fail to exceed Wave 3 high) — this is very bearish.
                    Wave 5 targets: 0.618x–1.0x of Wave 1, or 0.382x of Waves 1-3 net distance.
                    Wave 5 can take the form of an ending diagonal (overlapping waves).

                    === CHART PATTERN CORRELATION ===
                    - Rising wedge forming AS Wave 5 = ending diagonal = bearish reversal imminent
                    - Broadening top during Wave 5 = instability, trend exhaustion
                    - Double top at Wave 5 high vs Wave 3 high = truncated Wave 5
                    - Head and shoulders with Wave 3 as the head, Wave 5 as right shoulder (bearish reversal)
                    - Ascending channel reaching upper boundary = Wave 5 target zone

                    === CANDLE PATTERN CORRELATION ===
                    - Evening star / shooting star at Wave 5 terminus
                    - Bearish engulfing at Wave 5 high on higher TF
                    - Doji at the high = indecision before reversal
                    - Dark cloud cover completing Wave 5
                    - Long upper wicks showing selling pressure
                    - Exhaustion gap (gap up followed by reversal) at Wave 5 high

                    === INDICATOR CORRELATION ===
                    - RSI: BEARISH DIVERGENCE vs Wave 3 (lower RSI on higher/equal price) — KEY SIGNAL
                    - MACD: BEARISH DIVERGENCE vs Wave 3 (lower MACD on higher price)
                    - Volume: DECLINING vs Wave 3 (lower participation = exhaustion)
                    - Stochastic: Reaching overbought but with bearish divergence
                    - On Balance Volume: Diverging from price (OBV flat while price rises)
                    """)
                .stageDetection("""
                    - EARLY_WAVE_5: First impulse from Wave 4 end, breaking out of correction pattern
                    - MID_WAVE_5: Sub-wave iii developing, approaching Wave 3 high area
                    - LATE_WAVE_5: Approaching extension targets, divergence building
                    - WAVE_5_EXHAUSTION: Near target, strong divergence on RSI/MACD, volume fading
                    - WAVE_5_COMPLETE: 5-wave structure done, reversal candle appearing
                    - TRUNCATED: Wave 5 failed to exceed Wave 3 high — very bearish
                    """)
                .entryRules("""
                    === TRADING OPPORTUNITY: Ride Wave 5 (from Wave 4 completion) ===
                    LONG ENTRY (Wave 5 ride):
                    - Entry: at Wave 4 completion (see Wave 4 skill for details)
                    - SL: below Wave 4 low
                    - TP: Wave 5 target = Wave 4 end + (0.618x to 1.0x Wave 1 length)
                    - Monitor for divergence — tighten stops as divergence builds

                    === TRADING OPPORTUNITY: Fade Wave 5 completion (REVERSAL) ===
                    ANTICIPATORY SHORT at Wave 5 exhaustion:
                    - Entry zone: at Wave 5 target (0.618-1.0x of Wave 1), with confirmed divergence
                    - SL: above Wave 5 high (1-2% buffer above the terminus)
                    - TP1: Wave 4 area (first support)
                    - TP2: Wave A target = 38.2%–50% of entire 5-wave advance
                    - TP3: Wave C target = deep retracement
                    - Requires: bearish divergence on RSI + MACD + reversal candle pattern + declining volume
                    - R:R ratio: excellent if divergence is confirmed (3:1 to 6:1)

                    === TRADING OPPORTUNITY: Prepare for ABC correction ===
                    After Wave 5 completes, the ENTIRE 5-wave advance will be corrected.
                    This is the setup phase for Wave A short / Wave C short entries.
                    """)
                .indicatorRules("""
                    RSI: MUST show bearish divergence vs Wave 3 peak. This is the #1 signal.
                    RSI making lower high while price makes equal/higher high = Wave 5 exhaustion.
                    If RSI makes a NEW high above Wave 3 → Flag: 'RSI too strong for Wave 5 — possibly extended Wave 3.'

                    MACD: Should show bearish divergence vs Wave 3. MACD histogram lower.
                    If MACD makes new high → Flag: 'MACD not confirming Wave 5 — Wave 3 may be extending.'

                    Volume: Should be LOWER than Wave 3. Declining volume = exhaustion.
                    If volume is HIGHER than Wave 3 → Flag: 'Volume too high for Wave 5 — possible Wave 3 extension.'

                    Stochastic: Overbought with bearish crossdown at Wave 5 end = timing signal for reversal.

                    Bollinger Bands: Price may touch or pierce upper band then retreat inside = reversal signal.
                    """)
                .invalidationRules("""
                    INVALIDATED if:
                    - Wave 5 shows stronger momentum than Wave 3 (higher RSI, MACD, volume)
                      — this is likely an extended Wave 3, not Wave 5
                    - Sub-structure shows a clear 5-wave impulse with NO divergence — Wave 3 extension
                    - Price accelerates with gaps and volume expansion — not exhaustion behavior
                    - If Wave 5 is labeled but then price continues strongly — the count is wrong
                    """)
                .ambiguityQuestions("""
                    Q1: Is this Wave 5 or an extension of Wave 3? Check — is there bearish divergence?
                    Q2: Could this be a truncated Wave 5? Price failed to exceed Wave 3 high.
                    Q3: Is this an ending diagonal (overlapping waves) or a standard impulse for Wave 5?
                    Q4: How do we distinguish Wave 5 exhaustion from a Wave 3 that's just pausing?
                    Q5: Wave 5 has exceeded 1.0x of Wave 1 — is it extending or is the count wrong?
                    """)
                .crossVerificationRules("""
                    CV-1: Waves 1-4 must be confirmed before labeling Wave 5.
                    CV-2: Wave 5 must show 5-wave sub-structure (or 3-wave if ending diagonal).
                    CV-3: BEARISH DIVERGENCE on RSI and MACD vs Wave 3 = required for high confidence.
                    No divergence → Flag: 'Missing divergence — Wave 5 ID weakened. Possibly still in Wave 3.'
                    CV-4: Volume must be lower than Wave 3. Higher volume → Flag: 'Not typical Wave 5.'
                    CV-5: Check for topping patterns — rising wedge, H&S, double top correlating with Wave 5.
                    Pattern present → higher confidence in reversal.
                    CV-6: Wave 5 target at Fibonacci extension (0.618x or 1.0x of Wave 1 from Wave 4 end).
                    Price at target + divergence = highest confidence Wave 5 completion.
                    """)
                .build();
    }

    // ─── Wave A: First Corrective Leg ──────────────────────────────────────────

    private CopilotSkill buildWaveASkill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Wave A — First Corrective Leg")
                .description("Elliott Wave A — first leg of ABC correction, often mistaken for a normal pullback")
                .category("WAVE")
                .skillKey("wave_a")
                .isSystemSeed(true)
                .identificationRules("""
                    Wave A is the FIRST leg of the corrective sequence following a completed 5-wave impulse.
                    Wave A can be either 5-wave impulse (in zigzag corrections) or 3-wave corrective (in flat corrections).
                    If Wave A is 5-wave → expect a zigzag correction (deeper, sharper).
                    If Wave A is 3-wave → expect a flat or triangle correction (shallower, longer).
                    Wave A is often MISTAKEN for a normal pullback within the prior trend.
                    Sentiment remains bullish during Wave A — "buy the dip" mentality dominates.

                    === CHART PATTERN CORRELATION ===
                    - Head and shoulders completion often coincides with Wave A decline
                    - Double top confirmed (neckline break) = Wave A in progress
                    - Rising wedge breakdown = Wave A starting
                    - Descending channel forming = Wave A structure
                    - Failed ascending triangle breakout → reversal = Wave A beginning

                    === CANDLE PATTERN CORRELATION ===
                    - Evening star / shooting star at Wave 5 top (Wave A origin)
                    - Bearish engulfing on weekly/daily at the trend peak
                    - Three black crows as Wave A accelerates
                    - Large bearish marubozu candles breaking support levels
                    - Gap down (breakaway gap) early in Wave A

                    === INDICATOR CORRELATION ===
                    - RSI: Breaking below 50 after the Wave 5 divergence
                    - MACD: Bearish crossover confirmed, histogram turning negative
                    - Volume: Expanding on declines (distribution), contracting on rallies
                    - ADX: May still be elevated from prior trend, then starts declining
                    - Moving averages: Price crossing below 20 EMA, approaching 50 EMA
                    """)
                .stageDetection("""
                    - WAVE_A_STARTING: First breakdown from Wave 5 high, could still be a normal pullback
                    - WAVE_A_ACCELERATING: Breaking below key support levels (Wave 4 area)
                    - WAVE_A_NEARING_END: Approaching Fibonacci support (38.2%–50% of entire 5-wave advance)
                    - WAVE_A_COMPLETE: 5-wave or 3-wave sub-structure complete on lower TF
                    """)
                .entryRules("""
                    === TRADING OPPORTUNITY: Short Wave A (trend reversal trade) ===
                    SHORT ENTRY (Wave A ride):
                    - Entry zone: after confirming Wave 5 completion with bearish reversal pattern
                    - SL: above Wave 5 high
                    - TP1: Wave 4 area (first support)
                    - TP2: 38.2% retracement of the full 5-wave advance
                    - Requires: H&S or double top confirmed + MACD bearish crossover

                    === TRADING OPPORTUNITY: Prepare for Wave B rally (counter-trend long) ===
                    At Wave A completion, a significant counter-trend rally (Wave B) follows.
                    This is the setup phase — identify Wave A completion for Wave B entry.

                    === TRADING OPPORTUNITY: Wait for Wave C (safest correction trade) ===
                    The best correction trade is often the Wave C short after Wave B completes.
                    Wave A defines the correction type (zigzag vs flat) which determines Wave C target.
                    """)
                .indicatorRules("""
                    RSI: Breaking below 50 confirms bearish shift. RSI was divergent at Wave 5 top.
                    If RSI rebounds above 50 quickly → possibly just a pullback, not Wave A.

                    MACD: Bearish crossover. Histogram turning negative and expanding.
                    If MACD quickly recovers above zero → Flag: 'Quick recovery — may be Wave 4, not Wave A.'

                    Volume: Distribution pattern — volume expanding on down moves, contracting on bounces.
                    If volume is absent on the decline → Flag: 'Low volume decline — possibly just a pullback.'

                    Moving Averages: 20 EMA broken, approaching 50 EMA. 50 EMA acts as first major support.
                    """)
                .invalidationRules("""
                    INVALIDATED if:
                    - Price makes a new high above Wave 5 high (Wave 5 not complete, or Wave 5 extending)
                    - The decline shows only a 3-wave corrective structure but then resumes the uptrend strongly
                      (this was just a pullback within an extending Wave 5, not Wave A)
                    - Volume is completely absent — no distribution, likely just a normal pause
                    - RSI quickly rebounds above 60 and MACD recrosses bullish — the trend is intact
                    """)
                .ambiguityQuestions("""
                    Q1: Is this Wave A of a correction or just a sub-wave iv pullback within an extending Wave 3/5?
                    Q2: Is Wave A impulsive (5-wave) pointing to a zigzag, or corrective (3-wave) pointing to a flat?
                    Q3: How do we distinguish Wave A start from a normal profit-taking pullback?
                    Q4: The decline has been sharp but what if this is the beginning of a new impulse down?
                    """)
                .crossVerificationRules("""
                    CV-1: Wave 5 must be confirmed complete (with divergence) before labeling Wave A.
                    If Wave 5 may still be in progress → Flag: 'Premature Wave A label.'

                    CV-2: Check Wave A sub-structure on lower TF — 5-wave = zigzag coming, 3-wave = flat/triangle.

                    CV-3: A topping pattern (H&S, double top, rising wedge) should be visible at the origin.
                    No topping pattern → lower confidence in correction thesis.

                    CV-4: MACD bearish crossover and RSI below 50 should both be present.
                    Missing either → Flag: 'Momentum hasn't fully shifted.'

                    CV-5: Higher TF context — is this correction at the same degree or is the entire 5-wave
                    advance just a sub-wave of a larger pattern?
                    """)
                .build();
    }

    // ─── Wave B: Counter-Trend Rally/Decline ───────────────────────────────────

    private CopilotSkill buildWaveBSkill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Wave B — Counter-Trend Move")
                .description("Elliott Wave B — deceptive counter-trend move, often traps traders, sets up Wave C")
                .category("WAVE")
                .skillKey("wave_b")
                .isSystemSeed(true)
                .identificationRules("""
                    Wave B is a counter-trend move WITHIN the corrective sequence.
                    Wave B is the MOST DECEPTIVE wave — it tricks traders into thinking the prior trend has resumed.
                    In a zigzag: Wave B retraces 38.2%–78.6% of Wave A (3-wave structure).
                    In a flat: Wave B retraces ~100% of Wave A (strong, nearly reaches prior high).
                    In an expanded flat: Wave B EXCEEDS Wave A origin (makes new high in bull case) — ultimate bull trap.
                    Wave B must show 3-wave corrective sub-structure (ABC) — it is ALWAYS corrective.

                    === CHART PATTERN CORRELATION ===
                    - Bull trap / failed breakout pattern = expanded flat Wave B exceeding prior high
                    - Ascending triangle during Wave B = building false confidence before Wave C decline
                    - Right shoulder of H&S = Wave B in bearish case
                    - Bear flag that breaks down = Wave B ending and Wave C beginning
                    - Double top (Wave B high vs Wave 5 high) = expanded flat setup

                    === CANDLE PATTERN CORRELATION ===
                    - Strong bullish candles during Wave B deceive traders (looks like trend resuming)
                    - Shooting star / evening star at Wave B terminus
                    - Bearish engulfing at Wave B peak = Wave C starting
                    - Gap up during Wave B (later becomes an exhaustion gap)
                    - Doji at Wave B high = indecision before Wave C crash

                    === INDICATOR CORRELATION ===
                    - RSI: Rallies but fails to match Wave 5 reading = continued divergence
                    - MACD: May approach but NOT exceed the prior trend's MACD levels
                    - Volume: Lower than the prior trend's volume — weakening participation
                    - Sentiment: Overly bullish despite technical deterioration (bull trap)
                    - Moving averages: Price may reclaim 20 EMA but 50 EMA acts as resistance
                    """)
                .stageDetection("""
                    - WAVE_B_STARTING: First bounce from Wave A low, uncertain if correction continues
                    - WAVE_B_RALLYING: Strong counter-trend move, approaching Wave A origin / prior highs
                    - WAVE_B_AT_TARGET: Near Fibonacci retracement target of Wave A
                    - EXPANDED_B: Wave B exceeding Wave A origin — making new highs (expanded flat)
                    - WAVE_B_COMPLETE: Counter-trend move exhausted, reversal candle appearing
                    """)
                .entryRules("""
                    === TRADING OPPORTUNITY: Short at Wave B completion (Wave C entry — BEST correction trade) ===
                    ANTICIPATORY SHORT at Wave B peak:
                    - Entry zone: Wave B at 61.8%–100% retracement of Wave A, with reversal signal
                    - SL: above Wave B high (or above Wave 5 high if expanded flat)
                    - TP1: Wave A low (at minimum — Wave C equals Wave A)
                    - TP2: 1.618x extension of Wave A (Wave C extended target)
                    - TP3: 61.8%–78.6% retracement of entire prior 5-wave advance
                    - Requires: reversal candle at resistance + MACD divergence + declining volume
                    - R:R ratio: excellent (3:1 to 8:1)

                    === WARNING: Do NOT go long at Wave B top ===
                    Wave B is the ultimate bull trap. Going long at Wave B peak = catching a falling knife
                    when Wave C starts. The crash from Wave B to Wave C is often the fastest, most violent move.

                    === TRADING OPPORTUNITY: Long during Wave B (quick counter-trend) ===
                    RISKY LONG during Wave B rally:
                    - Entry: at Wave A completion with confirmed bottom
                    - SL: below Wave A low
                    - TP: 50%–78.6% retracement of Wave A
                    - MUST exit before Wave B completion — do not ride into Wave C
                    """)
                .indicatorRules("""
                    RSI: Should rally during Wave B but NOT match Wave 3 or Wave 5 peaks.
                    If RSI exceeds Wave 5 RSI → Flag: 'RSI too strong for Wave B — possible trend continuation.'

                    MACD: May rally but should remain below Wave 3/5 MACD peaks.
                    If MACD exceeds prior peaks → Flag: 'Trend may not be correcting — possible extended impulse.'

                    Volume: Should be LOWER than Wave A. Wave B on high volume is suspicious.
                    If volume exceeds Wave A → Flag: 'High volume Wave B — may be start of new impulse, not correction.'

                    Stochastic: Reaching overbought during Wave B = timing signal for Wave C entry (short).

                    Moving Averages: Wave B may retest 50 EMA / 200 EMA from below — these act as resistance.
                    """)
                .invalidationRules("""
                    INVALIDATED if:
                    - Wave B shows 5-wave impulse sub-structure (must be 3-wave corrective)
                    - Wave B significantly exceeds prior Wave 5 high with strong volume (expanded flat possible,
                      but if it keeps going → likely a new impulse, not Wave B)
                    - MACD and RSI both exceed their Wave 5 readings — trend likely continuing
                    - Volume is higher than the original trend — institutional buying, not a counter-trend trap
                    """)
                .ambiguityQuestions("""
                    Q1: Is this Wave B or has the prior trend resumed? The rally is strong and confident-looking.
                    Q2: Is this a regular flat (B near A origin) or expanded flat (B exceeds A origin)?
                    Q3: Wave B has exceeded the prior high — is this an expanded flat or a new Wave 1?
                    Q4: How to distinguish Wave B completion from a genuine trend resumption?
                    """)
                .crossVerificationRules("""
                    CV-1: Wave A must be confirmed before labeling Wave B.

                    CV-2: Wave B must show 3-wave corrective sub-structure.
                    5-wave structure → Flag: 'Impulsive structure — may not be Wave B.'

                    CV-3: Volume should be lower than Wave A and the prior trend.
                    Higher volume → Flag: 'Excessive volume for counter-trend Wave B.'

                    CV-4: Wave B should fail at key resistance (prior Wave 4 area, 50/200 EMA, prior highs).
                    Breaking through all resistance → Flag: 'No resistance holding — may be new trend.'

                    CV-5: Check for bull trap patterns (failed breakout, exhaustion gap, bearish engulfing).
                    Trap pattern present → high confidence Wave B is ending.
                    """)
                .build();
    }

    // ─── Wave C: Final Corrective Leg ──────────────────────────────────────────

    private CopilotSkill buildWaveCSkill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Wave C — Final Corrective Leg")
                .description("Elliott Wave C — powerful corrective impulse, often exceeds Wave A, completes the correction")
                .category("WAVE")
                .skillKey("wave_c")
                .isSystemSeed(true)
                .identificationRules("""
                    Wave C is the FINAL leg of the corrective sequence and is ALWAYS a 5-wave impulse.
                    Wave C often EQUALS Wave A in length (1:1 ratio) — most common target.
                    Wave C can extend to 1.618x of Wave A — especially in zigzag corrections.
                    Wave C is often the MOST VIOLENT and FASTEST leg of the correction.
                    Wave C catches the most traders off guard — they bought Wave B thinking the trend resumed.
                    In a flat: Wave C ends near or slightly beyond Wave A low.
                    In a zigzag: Wave C extends well beyond Wave A (deeper correction).

                    === CHART PATTERN CORRELATION ===
                    - H&S completion often coincides with Wave C decline (Wave B = right shoulder)
                    - Descending triangle breakdown = Wave C in progress
                    - Bear flag breakdown from Wave B area = Wave C launching
                    - Double bottom forming at Wave C termination = reversal setup (new impulse starting)
                    - Inverse H&S forming at Wave C end = next major buy signal

                    === CANDLE PATTERN CORRELATION ===
                    - Bearish marubozu / large red candles dominate Wave C
                    - Panic selling candles with high volume near Wave C end (capitulation)
                    - Hammer / bullish engulfing at Wave C bottom = reversal signal
                    - Morning star at key Fibonacci support = Wave C complete
                    - Exhaustion gap down near Wave C end (later becomes reversal gap)

                    === INDICATOR CORRELATION ===
                    - RSI: Reaches oversold (< 30) at Wave C end — may show POSITIVE divergence vs Wave A low
                    - MACD: Deeply negative, histogram may show positive divergence vs Wave A
                    - Volume: Spike on final Wave C decline (capitulation) followed by volume dry-up
                    - VIX/Fear gauges: Elevated (if market-wide correction)
                    - Moving averages: 20 EMA crosses below 50 EMA (death cross on lower TF)
                    """)
                .stageDetection("""
                    - WAVE_C_STARTING: First breakdown from Wave B high, beginning of the violent decline
                    - WAVE_C_ACCELERATING: Sub-wave iii of C — fastest, sharpest decline, panic selling
                    - WAVE_C_TARGET_ZONE: Approaching 1:1 with Wave A or 1.618x extension
                    - WAVE_C_CAPITULATION: Volume spike, RSI deeply oversold, panic visible
                    - WAVE_C_COMPLETE: 5-wave sub-structure done, positive divergence forming, reversal candle
                    """)
                .entryRules("""
                    === TRADING OPPORTUNITY: Ride Wave C short (continuation of correction) ===
                    SHORT ENTRY at Wave B completion:
                    - Entry zone: at Wave B peak with confirmed reversal pattern
                    - SL: above Wave B high
                    - TP1: Wave A low (1:1 target — most common)
                    - TP2: 1.618x extension of Wave A from Wave B peak
                    - Requires: bearish reversal pattern + MACD bearish crossover + declining volume on Wave B

                    === TRADING OPPORTUNITY: Buy Wave C completion (NEW TREND — best opportunity) ===
                    ANTICIPATORY LONG at Wave C termination (catching the next Wave 1):
                    - Entry zone: at 1:1 or 1.618x extension of Wave A, at key Fibonacci support
                    - SL: below Wave C low (1-2% buffer)
                    - TP1: Wave B high (first resistance)
                    - TP2: Prior Wave 5 high (if new impulse begins)
                    - TP3: Full new impulse targets
                    - Requires: 5-wave sub-structure complete + RSI positive divergence + capitulation volume + reversal candle
                    - R:R ratio: excellent (5:1 to 10:1 if catching a new trend)

                    === TRADING OPPORTUNITY: This is where the next big move begins ===
                    Wave C completion = Wave 1 of new impulse starting.
                    This is equivalent to buying at the bottom of a major correction.
                    """)
                .indicatorRules("""
                    RSI: Deeply oversold at Wave C end. POSITIVE DIVERGENCE vs Wave A low is the KEY signal
                    that the correction is ending. RSI making higher low while price makes lower low = buy signal.

                    MACD: Deeply negative but may show positive divergence vs Wave A.
                    MACD histogram positive divergence = Wave C ending.
                    If MACD makes new extreme without divergence → Wave C may be extending.

                    Volume: CAPITULATION SPIKE near Wave C end. Then volume dries up as selling is exhausted.
                    Capitulation volume = "blood in the streets" = best buying opportunity.

                    Stochastic: Deeply oversold, then bullish crossup = timing signal for reversal entry.

                    Fibonacci: Wave C ending at 61.8% or 78.6% retracement of the entire 5-wave advance.
                    """)
                .invalidationRules("""
                    INVALIDATED if:
                    - Wave C sub-structure shows only 3 waves (must be 5-wave impulse)
                    - Wave C exceeds 2.618x of Wave A without any divergence (possible new trend down, not correction)
                    - MACD makes new extreme without any divergence building (decline has more to go)
                    - No capitulation volume or reversal candle at supposed Wave C end
                    - Price immediately continues lower after supposed Wave C end — new impulse down, not correction end
                    """)
                .ambiguityQuestions("""
                    Q1: Is this Wave C or Wave 3 of a new bearish impulse? Check higher TF structure.
                    Q2: Has Wave C reached its target (1:1 or 1.618x of A)? Could it extend further?
                    Q3: Is the positive divergence genuine or is the decline just pausing before more downside?
                    Q4: In a flat correction, Wave C should end near Wave A low. In a zigzag, it goes beyond. Which is this?
                    """)
                .crossVerificationRules("""
                    CV-1: Waves A and B must be confirmed before labeling Wave C.

                    CV-2: Wave C must show 5-wave impulse sub-structure on lower TF.
                    Only 3 waves → Flag: 'Incomplete Wave C — may have more downside.'

                    CV-3: Wave C target — check if price has reached 1:1 with Wave A.
                    Not yet at target → Flag: 'Wave C may not be complete.'

                    CV-4: Positive divergence on RSI/MACD vs Wave A low.
                    No divergence → Flag: 'No divergence yet — Wave C may extend.'

                    CV-5: Reversal chart pattern forming at Wave C end (double bottom, inv H&S).
                    Pattern present → highest confidence. No pattern → wait for confirmation.

                    CV-6: Fibonacci retracement of entire 5-wave advance — Wave C should end at 38.2%, 50%, 61.8%, or 78.6%.
                    Random endpoint → Flag: 'No Fibonacci support — uncertain if correction is complete.'
                    """)
                .build();
    }

    // ─── Leading Diagonal ──────────────────────────────────────────────────────

    private CopilotSkill buildLeadingDiagonalSkill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Leading Diagonal")
                .description("Leading diagonal — overlapping wedge in Wave 1 or Wave A position, signals strong follow-through")
                .category("WAVE")
                .skillKey("leading_diagonal")
                .isSystemSeed(true)
                .identificationRules("""
                    A leading diagonal appears ONLY in Wave 1 or Wave A positions.
                    It is a 5-wave structure where waves OVERLAP (Wave 4 enters Wave 1 territory).
                    Each wave subdivides into 3 waves (3-3-3-3-3 structure) — exception to normal impulse rules.
                    The shape is a CONTRACTING WEDGE — converging trendlines connecting waves 1,3,5 and 2,4.
                    It signals a POWERFUL move to follow — when a leading diagonal ends, Wave 3 (or C) explodes.

                    === CHART PATTERN CORRELATION ===
                    - Falling wedge (bullish leading diagonal) or rising wedge (bearish leading diagonal)
                    - The wedge IS the leading diagonal — they are the same structure
                    - Breakout from the wedge = start of Wave 3 or Wave C
                    - Volume typically contracts within the diagonal and expands on breakout

                    === CANDLE PATTERN CORRELATION ===
                    - Overlapping candles within the wedge (no clean impulse moves)
                    - Small-bodied candles, alternating direction
                    - Bullish engulfing / breakout candle at diagonal completion
                    - Volume expanding on the breakout candle

                    === INDICATOR CORRELATION ===
                    - RSI: Oscillating, not trending — reflecting the overlapping nature
                    - MACD: Compressed within the diagonal, ready to expand on breakout
                    - Volume: Contracting within, expanding on breakout
                    - Bollinger Bands: Squeezing during diagonal formation
                    """)
                .stageDetection("""
                    - LEG_1: First move in new direction (3-wave structure)
                    - LEG_2: Correction back, overlapping later waves
                    - LEG_3: Second move, advancing but overlapping Leg 1
                    - LEG_4: Correction, entering Leg 1 territory (overlap confirmed)
                    - LEG_5: Final move to the apex area of the wedge
                    - DIAGONAL_COMPLETE: 5 legs done, breakout imminent
                    - THRUST: Explosive move following diagonal completion
                    """)
                .entryRules("""
                    === TRADING OPPORTUNITY: Buy breakout from leading diagonal (high conviction) ===
                    ENTRY at diagonal completion:
                    - Entry zone: at completion of Leg 5, or on breakout above the upper trendline
                    - SL: below Leg 5 low (tight stop due to wedge structure)
                    - TP1: Width of the wedge projected from breakout point
                    - TP2: 1.618x extension (Wave 3 target if this was Wave 1)
                    - Requires: breakout candle with volume expansion + Bollinger Bands opening
                    - R:R ratio: excellent (3:1 to 6:1) due to tight stop and explosive follow-through

                    NOTE: The move AFTER a leading diagonal is typically the strongest part of the entire sequence.
                    """)
                .indicatorRules("""
                    RSI: Oscillating within range during diagonal, then breaking out of range on completion.
                    MACD: Compressed, then expanding rapidly on breakout = confirmation.
                    Volume: Contracting = coiling. Breakout volume should be significantly above average.
                    Bollinger Bands: Squeeze during diagonal → expansion on breakout.
                    """)
                .invalidationRules("""
                    INVALIDATED if:
                    - Waves DO NOT overlap (this is a regular impulse, not a diagonal)
                    - Any wave shows 5-wave impulse sub-structure (leading diagonals have 3-3-3-3-3)
                    - The trendlines are not converging (not a wedge shape)
                    - Wave 5 exceeds the converging trendline significantly (diagonal structure broken)
                    - Breakout fails and price returns inside the diagonal with force
                    """)
                .ambiguityQuestions("""
                    Q1: Is this a leading diagonal (Wave 1/A) or ending diagonal (Wave 5/C)?
                    Position in the larger structure determines this.
                    Q2: Are the overlaps genuine or is this simply a choppy impulse?
                    Q3: Is each leg truly 3-wave or could some be 5-wave (which would invalidate diagonal)?
                    """)
                .crossVerificationRules("""
                    CV-1: Must be in Wave 1 or Wave A position. If in Wave 5 position → ending diagonal instead.
                    CV-2: All 5 legs must show 3-wave sub-structure. Any 5-wave leg → not a diagonal.
                    CV-3: Trendlines must converge (contracting wedge). Expanding → broadening formation, not diagonal.
                    CV-4: Wave 4 must enter Wave 1 territory (overlap required).
                    CV-5: Breakout must show volume expansion. No volume → possible false breakout.
                    """)
                .build();
    }

    // ─── Ending Diagonal ───────────────────────────────────────────────────────

    private CopilotSkill buildEndingDiagonalSkill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Ending Diagonal")
                .description("Ending diagonal — exhaustion wedge in Wave 5 or Wave C, signals sharp reversal")
                .category("WAVE")
                .skillKey("ending_diagonal")
                .isSystemSeed(true)
                .identificationRules("""
                    An ending diagonal appears ONLY in Wave 5 or Wave C positions.
                    It is a 5-wave structure with overlapping waves forming a CONTRACTING WEDGE.
                    Sub-structure: 3-3-3-3-3 (all waves are corrective, showing exhaustion).
                    It signals EXTREME EXHAUSTION — the trend is making its final push.
                    Ending diagonals are followed by SHARP reversals that retrace the entire diagonal quickly.
                    The reversal after an ending diagonal is often the start of a major trend change.

                    === CHART PATTERN CORRELATION ===
                    - Rising wedge at end of uptrend = ending diagonal in Wave 5 (bearish)
                    - Falling wedge at end of downtrend = ending diagonal in Wave C (bullish)
                    - The wedge IS the ending diagonal pattern
                    - Reversal after the wedge is sharp — often faster than the wedge formation itself

                    === CANDLE PATTERN CORRELATION ===
                    - Decreasing candle bodies toward the apex (exhaustion)
                    - Long wicks / doji candles near completion
                    - Sharp reversal candle at diagonal end (engulfing, evening/morning star)
                    - Exhaustion gap at the final high (later filled quickly)

                    === INDICATOR CORRELATION ===
                    - RSI: Strong bearish/bullish divergence — price making new extremes, RSI failing
                    - MACD: Divergence confirmed, histogram fading
                    - Volume: Declining throughout the diagonal (exhaustion)
                    - Stochastic: Extended overbought/oversold with crossover at completion
                    """)
                .stageDetection("""
                    - FORMING: Overlapping waves visible, converging trendlines identified
                    - LEG_3_OR_4: Mid-diagonal, pattern becoming clearer
                    - LEG_5_APPROACHING: Near the apex, divergence building
                    - DIAGONAL_COMPLETE: All 5 legs done, reversal imminent
                    - SHARP_REVERSAL: Post-diagonal reversal underway — typically retraces entire diagonal
                    """)
                .entryRules("""
                    === TRADING OPPORTUNITY: Fade the ending diagonal (reversal trade — excellent R:R) ===
                    ANTICIPATORY REVERSAL at diagonal completion:
                    - Entry zone: at completion of Leg 5 (near wedge boundary), with divergence confirmed
                    - SL: beyond the diagonal extreme (Leg 5 high/low + small buffer)
                    - TP1: Start of the diagonal (Wave 4 end) — this is typically reached FAST
                    - TP2: Start of the prior wave (Wave 3 end or Wave B end)
                    - TP3: Full correction targets (if this was Wave 5 of an entire impulse)
                    - Requires: divergence + reversal candle + declining volume in the diagonal
                    - R:R ratio: 4:1 to 10:1 (one of the best reversal setups in Elliott)

                    CONFIRMATION ENTRY (break of diagonal trendline):
                    - Entry: when price breaks below/above the diagonal lower/upper trendline
                    - SL: beyond the diagonal extreme
                    - TP: same as above
                    - This is the safer entry but slightly worse R:R
                    """)
                .indicatorRules("""
                    RSI: MUST show divergence. RSI failing to confirm new price extremes = exhaustion.
                    MACD: MUST show divergence. MACD histogram declining with each push = key signal.
                    Volume: MUST be declining. If volume is increasing → likely not an ending diagonal.
                    Stochastic: Extended in overbought/oversold with crossover = timing trigger.
                    """)
                .invalidationRules("""
                    INVALIDATED if:
                    - Not in Wave 5 or Wave C position (leading diagonal instead)
                    - Volume is INCREASING (exhaustion requires declining volume)
                    - No divergence on RSI/MACD (the trend still has momentum)
                    - Breakout from diagonal continues strongly (not a reversal — diagonal thesis wrong)
                    - Sub-structure shows 5-wave legs (ending diagonals should be 3-3-3-3-3)
                    """)
                .ambiguityQuestions("""
                    Q1: Is this an ending diagonal (Wave 5/C) or leading diagonal (Wave 1/A)?
                    Q2: Could this wedge simply be a continuation pattern (bull/bear flag) rather than a diagonal?
                    Q3: Is the divergence strong enough to confirm exhaustion, or could this extend?
                    """)
                .crossVerificationRules("""
                    CV-1: Must be in Wave 5 or Wave C position (prior waves 1-4 or A-B confirmed).
                    CV-2: All legs must be 3-wave corrective structure. Any 5-wave leg → not a valid diagonal.
                    CV-3: Overlapping waves required (Wave 4 enters Wave 1 territory).
                    CV-4: Divergence on RSI AND MACD required for high confidence.
                    CV-5: Volume must be declining — this confirms exhaustion.
                    CV-6: Trendlines must converge. If they diverge → broadening pattern, not diagonal.
                    """)
                .build();
    }

    // ─── Extended Wave 3 ───────────────────────────────────────────────────────

    private CopilotSkill buildExtendedWave3Skill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Extended Wave 3")
                .description("Extended Wave 3 — most common extension, wave within a wave, multiple sub-wave entries")
                .category("WAVE")
                .skillKey("extended_wave_3")
                .isSystemSeed(true)
                .identificationRules("""
                    An extended Wave 3 occurs when Wave 3 subdivides into its own 5-wave impulse that stretches
                    to 2.618x or 4.236x of Wave 1 (much beyond the standard 1.618x).
                    Extension is the MOST COMMON in Wave 3 (about 60% of impulse waves extend Wave 3).
                    When Wave 3 extends, the internal sub-waves become prominent and tradeable on their own.
                    Sub-wave iii of 3 is the strongest part — this is where parabolic moves happen.
                    You often see "nine waves" in the impulse — Waves 1, 2, then nine sub-waves of 3, then 4, 5.

                    === CHART PATTERN CORRELATION ===
                    - Channel breakout mid-Wave 3 extension (price exits the 1-2 channel)
                    - Multiple ascending triangle breakouts within the extension
                    - Bull flags within sub-waves of the extension (each flag → next sub-wave)
                    - No topping patterns anywhere — any distribution pattern is false

                    === CANDLE PATTERN CORRELATION ===
                    - Consecutive breakaway and runaway gaps
                    - Long-bodied bullish candles with minimal wicks
                    - Three white soldiers pattern appearing multiple times
                    - Volume climax candles at sub-wave iii peak

                    === INDICATOR CORRELATION ===
                    - RSI: Persistently above 60-70, may reach 80-90 (extreme overbought is normal)
                    - MACD: Making successive new highs, histogram largest of entire move
                    - Volume: Persistently high, each sub-wave advance on expanding volume
                    - ADX: Well above 30, strong trending condition
                    - Bollinger Bands: Walking the upper band for extended period
                    """)
                .stageDetection("""
                    - WAVE_3_i: First sub-wave impulse — looks like normal Wave 3 start
                    - WAVE_3_ii: First pullback — brief, shallow
                    - WAVE_3_iii: Strongest part — parabolic move possible, gaps and volume climax
                    - WAVE_3_iv: Brief consolidation (triangle, flag), maintains momentum
                    - WAVE_3_v: Final push of the extension, some divergence starting
                    - EXTENSION_COMPLETE: 5 sub-waves done, MACD starting to compress for Wave 4
                    """)
                .entryRules("""
                    === TRADING OPPORTUNITY: Multiple entries within the extension ===
                    SUB-WAVE ii and iv ENTRIES (pullback entries within extending Wave 3):
                    - Entry: at 38.2%–50% retracement of prior sub-wave
                    - SL: below prior sub-wave low
                    - TP: next sub-wave extension target
                    - These are shallow, fast pullbacks — must be quick to enter
                    - Each pullback within the extension is a buy opportunity

                    === TRADING OPPORTUNITY: Don't try to call the top ===
                    WARNING: Extended Wave 3 will exceed all reasonable targets.
                    DO NOT short because "it's gone too far" — extensions can reach 4.236x or beyond.
                    Wait for confirmed 5-wave completion within the extension before looking for Wave 4 entry.

                    === TRADING OPPORTUNITY: Recognize you're in an extension ===
                    If Wave 3 has passed 1.618x and shows no completion signs → it's extending.
                    Add to position on pullbacks, trail stops, ride the trend.
                    """)
                .indicatorRules("""
                    RSI: Will be in persistent overbought territory. Do NOT use RSI overbought as sell signal.
                    RSI only matters for divergence at sub-wave v of the extension (completion signal).

                    MACD: Making new highs on each sub-wave advance. Strongest readings of entire structure.
                    MACD divergence at sub-wave v = extension ending signal.

                    Volume: Persistent and high. Sub-wave iii of 3 typically has the highest volume bar.
                    Volume declining after sub-wave iii → extension may be nearing completion.

                    ADX: Above 30 and rising = confirmed strong trend. Declining ADX = extension ending.
                    """)
                .invalidationRules("""
                    INVALIDATED if:
                    - MACD and RSI show divergence early (before 2.618x) — not truly extending
                    - Volume declines early in the supposed extension — no institutional participation
                    - Sub-structure doesn't show clear 5-wave impulse within Wave 3
                    - Price action becomes choppy and overlapping — not impulsive behavior
                    """)
                .ambiguityQuestions("""
                    Q1: Is this an extended Wave 3 or a normal Wave 3 followed by Waves 4-5?
                    The distinction matters for target calculation and stop placement.
                    Q2: How far can this extension go? Is 2.618x or 4.236x more likely?
                    Q3: At what point do we accept that Wave 3 is done and Wave 4 has started?
                    """)
                .crossVerificationRules("""
                    CV-1: Waves 1 and 2 must be confirmed. Wave 3 > 1.618x of Wave 1 → likely extending.
                    CV-2: Sub-structure must show clear 5-wave impulse within Wave 3.
                    CV-3: MACD must be making successive new highs. Flat MACD → not extending.
                    CV-4: Volume must be persistently high. Weak volume → not extending.
                    CV-5: Higher TF must support the trend direction — extension in a counter-trend wave is rare.
                    """)
                .build();
    }

    // ─── Flat Correction (3-3-5) ───────────────────────────────────────────────

    private CopilotSkill buildFlatCorrectionSkill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Flat Correction (3-3-5)")
                .description("Flat correction — sideways ABC where A and B are 3-wave, C is 5-wave, common in Wave 4")
                .category("WAVE")
                .skillKey("flat_correction")
                .isSystemSeed(true)
                .identificationRules("""
                    A flat correction has structure 3-3-5 (Wave A = 3 waves, Wave B = 3 waves, Wave C = 5 waves).
                    THREE TYPES:
                    1. Regular flat: B ends near A origin, C ends near or slightly beyond A end
                    2. Expanded flat: B EXCEEDS A origin (makes new high/low), C extends beyond A end
                    3. Running flat: B exceeds A origin, C does NOT reach A end (strongly trending market)

                    Flats are MOST COMMON in Wave 4 position (sideways, time-consuming).
                    Wave A being 3-wave (not 5-wave) is the KEY identifier — if A is 5-wave → zigzag, not flat.
                    Flats are sideways patterns — they correct through TIME more than through price.

                    === CHART PATTERN CORRELATION ===
                    - Rectangle / trading range = flat correction in progress
                    - Double bottom (regular flat) or lower low (expanded flat) at Wave C end
                    - Bull trap at Wave B high (expanded flat)
                    - Range-bound oscillation typical of flat correction

                    === CANDLE PATTERN CORRELATION ===
                    - Alternating bullish and bearish candles (choppy, directionless)
                    - Small bodies, many wicks (indecision)
                    - Reversal candles at the extremes of the range

                    === INDICATOR CORRELATION ===
                    - RSI: Oscillating around 50 (no clear trend)
                    - MACD: Near zero line, no strong direction, compressed
                    - Volume: Low and declining (no conviction)
                    - Bollinger Bands: Narrow bands, squeeze forming
                    - ADX: Below 20 (no trend)
                    """)
                .stageDetection("""
                    - FLAT_A: 3-wave decline (A leg) — modest, corrective
                    - FLAT_B: 3-wave rally approaching or exceeding A origin — deceptive strength
                    - FLAT_B_EXPANDED: B has exceeded A origin — expanded flat confirmed
                    - FLAT_C_STARTING: 5-wave decline beginning from B peak
                    - FLAT_C_TARGET: C approaching A low (regular) or extending beyond (expanded)
                    - FLAT_COMPLETE: C has completed 5-wave structure, reversal signal
                    """)
                .entryRules("""
                    === TRADING OPPORTUNITY: Fade the range (within the flat) ===
                    RANGE TRADE:
                    - Buy at range bottom (Wave A/C low area), sell at range top (Wave B high area)
                    - Tight stops outside the range boundaries
                    - Short-term trades only — exit at opposite boundary

                    === TRADING OPPORTUNITY: Buy Wave C completion (major setup) ===
                    LONG at flat correction end:
                    - Entry: at Wave C end (5-wave decline complete at or near Wave A low)
                    - SL: below Wave C low
                    - TP: prior high (Wave 3 or Wave 5 high) as next impulse begins
                    - Requires: 5-wave C complete + positive divergence + reversal candle

                    === TRADING OPPORTUNITY: Short expanded flat Wave C ===
                    SHORT at expanded flat B peak:
                    - Entry: at Wave B peak above prior high (bull trap)
                    - SL: above Wave B high
                    - TP: below Wave A low (expanded flat C target)
                    - Requires: reversal candle at B peak + volume divergence
                    """)
                .indicatorRules("""
                    RSI: Oscillating 40-60 range. No trending behavior. Divergence at C end = reversal signal.
                    MACD: Compressed near zero. Minimal directional bias.
                    Volume: Low — flat corrections are boring and low-participation.
                    Bollinger Bands: Squeezing — breakout from flat = next impulse wave.
                    """)
                .invalidationRules("""
                    INVALIDATED if:
                    - Wave A shows 5-wave impulse structure (this is a zigzag, not a flat)
                    - Wave C exceeds 1.618x of Wave A significantly (too deep for flat — may be impulse)
                    - Directional momentum builds during the pattern (trending, not corrective)
                    - Volume expands strongly — flats are low-volume, low-conviction patterns
                    """)
                .ambiguityQuestions("""
                    Q1: Is Wave A 3-wave (flat) or 5-wave (zigzag)? This determines the correction type.
                    Q2: Is this a regular flat, expanded flat, or running flat?
                    Q3: Could this "flat" actually be the start of a triangle (more legs to come)?
                    """)
                .crossVerificationRules("""
                    CV-1: Wave A must be 3-wave corrective. If 5-wave → zigzag, not flat.
                    CV-2: Wave B must retrace at least 90% of Wave A (unlike zigzag where B is shallower).
                    CV-3: Wave C must be 5-wave impulse. If 3-wave → pattern incomplete or different type.
                    CV-4: Most common in Wave 4 position — verify wave position in larger structure.
                    CV-5: Alternation — if Wave 2 was a zigzag, Wave 4 flat is expected (and vice versa).
                    """)
                .build();
    }

    // ─── Zigzag Correction (5-3-5) ─────────────────────────────────────────────

    private CopilotSkill buildZigzagCorrectionSkill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Zigzag Correction (5-3-5)")
                .description("Zigzag correction — sharp ABC where A and C are 5-wave impulses, common in Wave 2")
                .category("WAVE")
                .skillKey("zigzag_correction")
                .isSystemSeed(true)
                .identificationRules("""
                    A zigzag has structure 5-3-5 (Wave A = 5-wave impulse, Wave B = 3-wave corrective, Wave C = 5-wave impulse).
                    Zigzag is a SHARP correction — it corrects significantly in price.
                    Wave B typically retraces 38.2%–78.6% of Wave A (but NOT 100% — that's a flat).
                    Wave C typically equals Wave A in length (most common), or reaches 1.618x of Wave A.
                    Zigzags are MOST COMMON in Wave 2 position.
                    Double and triple zigzags (WXY, WXYXZ) can occur for deeper corrections.

                    === CHART PATTERN CORRELATION ===
                    - Channel — zigzag often fits neatly within a parallel corrective channel
                    - Falling channel (bearish zigzag) / rising channel (bullish zigzag)
                    - Wave C ending at channel boundary = pattern complete
                    - Double bottom / inverse H&S may form at zigzag completion

                    === CANDLE PATTERN CORRELATION ===
                    - Strong directional candles in Waves A and C (they are impulses)
                    - Corrective, overlapping candles in Wave B only
                    - Reversal candle at Wave C end = correction complete

                    === INDICATOR CORRELATION ===
                    - RSI: Falls significantly during zigzag (reaches 30 area in bearish zigzag)
                    - MACD: Makes new lows during Wave C, but may show divergence vs Wave A
                    - Volume: Higher than flat corrections (zigzags have impulsive legs)
                    """)
                .stageDetection("""
                    - ZIGZAG_A: 5-wave impulse decline — sharp drop
                    - ZIGZAG_B: 3-wave corrective rally — shallow, partial retrace
                    - ZIGZAG_C: 5-wave impulse decline — approaching/at 1:1 with Wave A
                    - ZIGZAG_COMPLETE: C has completed 5 waves, at channel boundary or Fibonacci support
                    """)
                .entryRules("""
                    === TRADING OPPORTUNITY: Buy zigzag completion (excellent in Wave 2 position) ===
                    LONG at zigzag end:
                    - Entry: at Wave C end, where C = A (1:1) or at channel boundary
                    - SL: below Wave C low (or below Wave 1 origin if this is Wave 2)
                    - TP: Wave 3 target = 1.618x extension of Wave 1
                    - Requires: 5-wave C complete + at channel boundary + reversal signal
                    - R:R: 3:1 to 8:1 (especially if setting up Wave 3 entry)

                    === TRADING OPPORTUNITY: Short during zigzag (ride Wave C) ===
                    SHORT at Wave B completion within zigzag:
                    - Entry: at Wave B peak (38.2%–61.8% retrace of A)
                    - SL: above Wave A origin
                    - TP: Wave C target = Wave A length projected from Wave B peak
                    """)
                .indicatorRules("""
                    RSI: Declines during A and C, bounces during B. Divergence at C end vs A end.
                    MACD: New low during C, but histogram may show positive divergence = ending signal.
                    Volume: Higher in A and C (impulse legs), lower in B (corrective).
                    """)
                .invalidationRules("""
                    INVALIDATED if:
                    - Wave A shows only 3 waves (this is a flat, not zigzag)
                    - Wave B retraces 100% of Wave A (this is a flat or expanded flat)
                    - Wave C shows only 3 waves (not a complete zigzag C leg)
                    - Correction is shallow and sideways (zigzags are sharp and deep)
                    """)
                .ambiguityQuestions("""
                    Q1: Is Wave A 5-wave (zigzag) or 3-wave (flat)? This determines the correction type.
                    Q2: Could this be a double zigzag (WXY) instead of a single zigzag?
                    Q3: Is Wave C equal to Wave A (1:1) or extending to 1.618x?
                    """)
                .crossVerificationRules("""
                    CV-1: Wave A must be 5-wave impulse. 3-wave A → flat, not zigzag.
                    CV-2: Wave B should retrace 38.2%–78.6% of A. B at 100% → flat, not zigzag.
                    CV-3: Wave C must be 5-wave impulse, typically 1:1 with Wave A.
                    CV-4: Corrective channel — both A and C should touch or approach the channel boundaries.
                    CV-5: Most common in Wave 2 position. Check alternation with Wave 4.
                    """)
                .build();
    }

    // ─── Complex Correction WXY ────────────────────────────────────────────────

    private CopilotSkill buildComplexCorrectionWXYSkill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Complex Correction (WXY)")
                .description("Complex correction — double/triple combination of corrective patterns connected by X waves")
                .category("WAVE")
                .skillKey("complex_correction_wxy")
                .isSystemSeed(true)
                .identificationRules("""
                    A complex correction is a combination of two or three simple corrections connected by X waves.
                    Structure: W-X-Y (double combination) or W-X-Y-X-Z (triple combination).
                    W, Y, and Z can each be a zigzag, flat, or triangle.
                    X waves are corrective connectors (typically 3-wave).
                    The most common combination: zigzag (W) + flat or triangle (Y).
                    Complex corrections are TIME corrections — they correct through duration, not price.
                    They are the MOST FRUSTRATING pattern to trade (choppy, overlapping, directionless).

                    === CHART PATTERN CORRELATION ===
                    - Extended sideways range that doesn't fit any single corrective pattern
                    - Multiple failed breakouts in both directions
                    - Each sub-pattern (W, Y) may show its own chart pattern (channel, triangle, etc.)
                    - Rectangle or broad consolidation range on higher TF

                    === CANDLE PATTERN CORRELATION ===
                    - Choppy, alternating candles for extended period
                    - False breakout candles in both directions
                    - Doji and spinning top candles dominate
                    - No persistent directional candles

                    === INDICATOR CORRELATION ===
                    - RSI: Oscillating around 50 for extended period, no trend
                    - MACD: Flat near zero line, multiple false crossovers
                    - Volume: Very low — no participation, no conviction
                    - ADX: Below 20 for extended period (trendless)
                    - Bollinger Bands: Extremely narrow squeeze building
                    """)
                .stageDetection("""
                    - W_FORMING: First corrective pattern (zigzag, flat, or triangle) developing
                    - W_COMPLETE: First pattern done, X wave connector starting
                    - X_CONNECTOR: Counter-trend move connecting W and Y
                    - Y_FORMING: Second corrective pattern developing
                    - Y_COMPLETE: Second pattern done — may be the end, or another X-Z could follow
                    - TRIPLE_Z: Third corrective pattern forming (W-X-Y-X-Z)
                    - COMPLEX_COMPLETE: All patterns done, new impulse about to begin
                    """)
                .entryRules("""
                    === TRADING OPPORTUNITY: Buy at complex correction completion (patient trade) ===
                    LONG at WXY completion:
                    - Entry: when Y completes (at Fibonacci support or channel boundary)
                    - SL: below the lowest point of the entire complex correction
                    - TP: next impulse wave targets (1.618x of prior Wave 1)
                    - Requires: final pattern (Y) showing completion signals + Bollinger squeeze breakout
                    - Key: PATIENCE — complex corrections take much longer than expected

                    === TRADING OPPORTUNITY: Avoid trading WITHIN complex corrections ===
                    WARNING: Trading within WXY is extremely difficult — multiple false signals.
                    Best approach: identify it, sit it out, and enter when the new impulse begins.
                    The breakout from a complex correction is often explosive (compressed energy release).

                    === TRADING OPPORTUNITY: Range extremes (advanced, quick trades) ===
                    If the range is identifiable:
                    - Buy at range bottom, sell at range top
                    - Very tight stops, quick profit-taking
                    - Only for experienced traders
                    """)
                .indicatorRules("""
                    RSI: Oscillating, no trend. Any breakout from the oscillation range = pattern ending.
                    MACD: Flat near zero. Breakout (histogram expanding) = new impulse starting.
                    Volume: Very low during pattern. Volume expansion = pattern ending.
                    Bollinger Bands: Extreme squeeze. Breakout from squeeze = pattern complete, new trend.
                    ADX: Below 20. Rising above 25 = trend emerging from correction.
                    """)
                .invalidationRules("""
                    INVALIDATED if:
                    - Strong directional move develops (this was an impulse, not a correction)
                    - Volume expands significantly in one direction
                    - RSI trends above 60 or below 40 for extended period (not oscillating)
                    - The pattern becomes too deep (exceeds typical correction proportions)
                    """)
                .ambiguityQuestions("""
                    Q1: Is this a double combination (WXY) or triple (WXYXZ)?
                    Q2: What type is each sub-pattern — zigzag, flat, or triangle?
                    Q3: Is Y complete or is there another X-Z to come?
                    Q4: Could this seemingly complex pattern actually be a simple expanding triangle?
                    """)
                .crossVerificationRules("""
                    CV-1: Must identify W pattern type (zigzag, flat, triangle) on lower TF.
                    CV-2: X wave must be corrective (3-wave). If 5-wave → not a connector, possible new impulse.
                    CV-3: Y must be a DIFFERENT type than W (guideline — combination means combining different forms).
                    CV-4: Total correction should be proportional to the prior impulse (not excessively deep or long).
                    CV-5: Bollinger Band squeeze = confirmation that complex correction is building energy for breakout.
                    """)
                .build();
    }

    // ─── Wave Triangle (corrective triangle in wave context) ───────────────────

    private CopilotSkill buildWaveTriangleSkill(Long userId) {
        return CopilotSkill.builder()
                .userId(userId)
                .name("Wave Triangle (Elliott)")
                .description("Elliott Wave triangle — ABCDE converging correction, appears in Wave 4 or Wave B, precedes final move")
                .category("WAVE")
                .skillKey("wave_triangle")
                .isSystemSeed(true)
                .identificationRules("""
                    An Elliott Wave triangle is a 5-leg corrective pattern (A-B-C-D-E) forming a converging shape.
                    Structure: 3-3-3-3-3 (each leg is a 3-wave correction).
                    Appears in Wave 4, Wave B, or Wave X positions (NEVER in Wave 2 or Wave A).
                    The triangle PRECEDES THE FINAL MOVE — after a triangle, the next wave is the LAST wave in its sequence.
                    In Wave 4: triangle → Wave 5 (final impulse).
                    In Wave B: triangle → Wave C (final correction leg).
                    Thrust (post-triangle move) typically equals the widest part of the triangle.

                    === CHART PATTERN CORRELATION ===
                    - Symmetrical triangle = most common Elliott triangle form
                    - Ascending triangle in bullish context (Wave 4 of uptrend)
                    - Descending triangle in bearish context
                    - Converging trendlines connecting A-C and B-D
                    - Triangle always PRECEDES the last move — critical for timing

                    === CANDLE PATTERN CORRELATION ===
                    - Decreasing range candles (each leg smaller than previous)
                    - Doji and inside bars near the apex
                    - Breakout candle (thrust) is usually strong and directional
                    - Volume drying up during triangle, expanding on thrust

                    === INDICATOR CORRELATION ===
                    - MACD: Compressing — histogram declining toward zero
                    - RSI: Narrowing range, oscillating around 50
                    - Volume: Declining with each leg (contracting participation)
                    - Bollinger Bands: Squeezing tightly — energy building
                    - ADX: Declining, below 20 (trendless within triangle)
                    """)
                .stageDetection("""
                    - LEG_A: First corrective leg — sets the triangle range
                    - LEG_B: Counter-move, establishes the other boundary
                    - LEG_C: Third leg, should not exceed A's extreme (converging)
                    - LEG_D: Fourth leg, should not exceed B's extreme (converging)
                    - LEG_E: Final leg — smallest, approaching the apex
                    - E_COMPLETE: All 5 legs done — thrust imminent
                    - THRUST: Post-triangle move underway (final impulse)
                    """)
                .entryRules("""
                    === TRADING OPPORTUNITY: Enter at E completion for the thrust (highest probability) ===
                    ENTRY at Leg E end:
                    - Entry: at completion of Leg E (smallest leg, near B-D trendline)
                    - SL: beyond the opposite triangle boundary (A-C trendline)
                    - TP: thrust target = widest part of triangle projected from breakout point
                    - Requires: 5 legs complete + MACD about to cross + Bollinger squeeze extreme
                    - R:R: 3:1 to 6:1

                    === TRADING OPPORTUNITY: Enter on thrust breakout (safer entry) ===
                    BREAKOUT ENTRY:
                    - Entry: on breakout beyond the A-C or B-D trendline
                    - SL: inside the triangle (below E low in bullish case)
                    - TP: thrust target
                    - Volume must expand on breakout

                    === CRITICAL INSIGHT: The thrust is the LAST move ===
                    After a Wave 4 triangle → Wave 5 is the final impulse.
                    After a Wave B triangle → Wave C is the final correction leg.
                    Plan your exit strategy accordingly — don't expect trend continuation after the thrust.
                    """)
                .indicatorRules("""
                    MACD: MUST compress during the triangle. Histogram approaching zero.
                    If MACD is expanding → Flag: 'Not a triangle — possible impulse.'
                    MACD crossover at E completion = timing signal for thrust entry.

                    RSI: Should oscillate in narrowing range. Trending RSI → not a triangle.

                    Volume: MUST decline with each successive leg. Volume expansion = thrust starting.
                    If volume is high during the triangle → Flag: 'High volume contradicts triangle thesis.'

                    Bollinger Bands: Should be in extreme squeeze at the apex.
                    """)
                .invalidationRules("""
                    INVALIDATED if:
                    - Any leg shows 5-wave impulse sub-structure (all legs must be 3-wave corrective)
                    - MACD is expanding (triangle requires compression)
                    - The pattern is in Wave 2 or Wave A position (triangles don't appear there)
                    - Trendlines diverge instead of converge (broadening, not triangle)
                    - C exceeds A, or D exceeds B (not converging = not a valid triangle)
                    - E exceeds D significantly (triangle structure broken)
                    """)
                .ambiguityQuestions("""
                    Q1: Is this a triangle (5-leg ABCDE) or just a series of overlapping corrections?
                    Q2: Is this Wave 4 or Wave B? (affects what comes after the thrust)
                    Q3: Is Leg E complete or still extending?
                    Q4: Could this be a leading/ending diagonal rather than a triangle?
                    Q5: The triangle is in Wave 4 position — is Wave 3 truly complete?
                    """)
                .crossVerificationRules("""
                    CV-1: Must be in Wave 4, Wave B, or Wave X position. Not valid in Wave 2 or Wave A.
                    CV-2: All 5 legs must be 3-wave corrective. Any 5-wave leg → not a triangle.
                    CV-3: Trendlines connecting A-C and B-D must converge.
                    CV-4: MACD must be compressing. Expanding → Flag: 'Not triangle behavior.'
                    CV-5: Volume must be declining. Each leg should have lower volume than the previous.
                    CV-6: The thrust target = widest part of triangle. This gives you the measured move target.
                    CV-7: After the thrust (Wave 5 or Wave C), expect a significant reversal.
                    """)
                .build();
    }

    // ─── Original Wave 4 skill (kept for backward compatibility) ───────────────

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
