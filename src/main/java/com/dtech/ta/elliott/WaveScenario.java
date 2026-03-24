package com.dtech.ta.elliott;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * A directional scenario grouping related wave hypotheses.
 *
 * All hypotheses within a scenario share the same directional outlook
 * but differ in the path or timing to get there.
 *
 * E.g., BULLISH_CONTINUATION scenario may contain:
 *   H1: Wave 4 zigzag → Wave 5 target 2100
 *   H2: Wave 4 triangle → Wave 5 target 2100
 *   H3: Wave 4 flat     → Wave 5 target 2100
 *
 * Scenarios are what the AI reasons over. Hypotheses are the specifics.
 * Code produces all scenarios. AI ranks them.
 */
@Data
@Builder
public class WaveScenario {

    public enum ScenarioDirection {
        BULLISH_CONTINUATION,   // Correction temporary, higher highs coming
        BEARISH_CONTINUATION,   // Correction temporary in bear trend, lower lows coming
        BULLISH_REVERSAL,       // Prior downtrend ending, new uptrend beginning
        BEARISH_REVERSAL,       // Prior uptrend ending, new correction/downtrend beginning
        RANGE_RESOLUTION        // Triangle / flat — explosive move either direction pending
    }

    private String id;            // e.g., "S1", "S2"
    private ScenarioDirection direction;
    private String directionLabel; // human-readable: "Bullish continuation — Wave 5 ahead"

    /** All valid hypotheses for this scenario (same direction, different paths) */
    private List<WaveHypothesis> hypotheses;

    // ── Shared decision points ────────────────────────────────────────────────

    /**
     * Prices that, when breached, resolve ambiguity between hypotheses or scenarios.
     * E.g., "Close above 1920 → all bullish hypotheses strengthened"
     *        "Close below 1780 → entire bullish scenario invalidated"
     */
    private List<DecisionPoint> decisionPoints;

    // ── Scenario-level invalidation ────────────────────────────────────────────

    /**
     * Price level that invalidates the entire scenario (all hypotheses within it).
     * Breaching this means the AI must reconsider from scratch.
     */
    private double scenarioInvalidation;

    /** The rule / reasoning behind the scenario-level invalidation */
    private String invalidationReason;

    // ── Pattern landscape ─────────────────────────────────────────────────────

    /** All patterns detected (BUILDING, WATCHING, CONFIRMED) relevant to this scenario */
    private List<PatternMatch> relevantPatterns;

    /** Patterns that SUPPORT this scenario direction */
    private List<PatternMatch> alignedPatterns;

    /** Patterns that CONTRADICT this scenario direction */
    private List<PatternMatch> conflictingPatterns;

    // ── Indicator posture for the scenario ───────────────────────────────────

    /**
     * Overall indicator alignment for this scenario direction.
     * Map of indicator → assessment.
     */
    private Map<String, String> indicatorAlignment;

    // ── Nested model ─────────────────────────────────────────────────────────

    @Data
    @Builder
    public static class DecisionPoint {
        private double priceLevel;
        private String label;
        private String ifBreachedAbove;
        private String ifBreachedBelow;
        private List<String> hypothesesAffected;
    }

    // ── Convenience ──────────────────────────────────────────────────────────

    public WaveHypothesis highestScoringHypothesis() {
        return hypotheses == null || hypotheses.isEmpty() ? null
                : hypotheses.stream()
                        .max(java.util.Comparator.comparingInt(WaveHypothesis::getTotalScore))
                        .orElse(null);
    }
}
