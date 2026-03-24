package com.dtech.ta.elliott;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * A fully-specified trading hypothesis derived from a wave count.
 *
 * Contains everything needed for the AI layer to evaluate and trade:
 * - The wave count it's derived from
 * - Fibonacci target zones (where the next wave ends)
 * - Invalidation level (where this count is definitively wrong)
 * - Expected chart patterns at the current wave position
 * - Matched chart patterns (if any are forming)
 * - All supporting and contradicting evidence (objective, no conclusions)
 *
 * Code never says "this IS the most likely count."
 * Code says: "IF this count is correct, here is everything implied by it."
 */
@Data
@Builder
public class WaveHypothesis {

    private String id;  // e.g., "H1", "H2" — for cross-reference in AI prompt

    /** The underlying wave count this hypothesis is derived from */
    private WaveCount waveCount;

    // ── Current position ──────────────────────────────────────────────────────

    /**
     * Human-readable description of where we are in the wave structure.
     * E.g., "Inside Wave 4 correction of daily impulse, sub-wave C of zigzag near completion"
     */
    private String currentPositionDescription;

    /** The direction of the NEXT expected major move */
    private boolean nextMajorMoveUp;

    // ── Fibonacci target zones ────────────────────────────────────────────────

    /**
     * Price zones where the next wave should end (ordered by priority).
     * E.g., [{"level": 2100, "ratio": "W5 = W1 projection"}, ...]
     */
    private List<FibTarget> targetZones;

    /** The most probable target zone (highest confluence) */
    private FibTarget primaryTarget;

    // ── Risk levels ───────────────────────────────────────────────────────────

    /**
     * Price level that definitively invalidates this wave count.
     * If breached, this hypothesis should be killed immediately.
     */
    private double invalidationLevel;

    /**
     * The rule that defines the invalidation.
     * E.g., "W4 cannot enter W1 territory — invalidated below 1780"
     */
    private String invalidationRule;

    /** Key intermediate decision level — breach favors or kills specific sub-hypotheses */
    private Double decisionLevel;
    private String decisionLevelMeaning;

    // ── Pattern confluence ────────────────────────────────────────────────────

    /** Chart patterns expected at this wave position */
    private List<PatternType> expectedPatterns;

    /** Chart patterns that have actually been detected (subset or overlap of expected) */
    private List<PatternMatch> matchedPatterns;

    // ── Indicator summary at current wave position ────────────────────────────

    /**
     * Current indicator posture for the AI.
     * Key = indicator name, Value = observation.
     * E.g., {"RSI": "41.2 — near oversold, approaching W4 support zone"}
     */
    private Map<String, String> indicatorSummary;

    // ── Time analysis ─────────────────────────────────────────────────────────

    /**
     * Expected duration of the next wave as a fraction of the prior same-degree wave.
     * E.g., 0.618 means next wave should last ~61.8% as long as the reference wave.
     */
    private Double nextWaveExpectedDuration;

    // ── Evidence (objective, no conclusion) ──────────────────────────────────

    private List<WaveEvidence> supportingEvidence;
    private List<WaveEvidence> contradictingEvidence;

    // ── Score breakdown ───────────────────────────────────────────────────────

    /** Total hypothesis score: wave count score + pattern confluence bonus */
    private int totalScore;

    /**
     * Score breakdown for transparency.
     * E.g., {"fibonacci": 28, "indicators": 15, "crossTf": 10, "patternConfluence": 15}
     */
    private Map<String, Integer> scoreBreakdown;

    // ── Fibonacci target model ────────────────────────────────────────────────

    @Data
    @Builder
    public static class FibTarget {
        private double level;
        private String ratio;        // e.g., "W5 = W1 projection", "100% retrace of W3"
        private String waveName;     // e.g., "W5", "WC"
        private double confidence;   // 0–1 weight relative to other targets
    }
}
