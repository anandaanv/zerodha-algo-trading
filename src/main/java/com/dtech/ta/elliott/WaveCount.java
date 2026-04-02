package com.dtech.ta.elliott;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * A single valid Elliott Wave count — a mapping from pivot to wave label,
 * with a Fibonacci fit score and indicator confirmation score.
 *
 * Multiple WaveCount objects will be generated for the same price action
 * (all valid interpretations). The ScenarioBuilder groups them into scenarios.
 */
@Data
@Builder
public class WaveCount {

    public enum WaveType {
        IMPULSE,        // 5-wave in trend direction (W1-W5)
        ZIGZAG,         // Sharp ABC correction
        FLAT,           // Sideways ABC correction
        EXPANDED_FLAT,  // Wave B exceeds Wave A origin
        RUNNING_FLAT,   // Wave C doesn't reach Wave A end
        TRIANGLE,       // 5-swing converging ABCDE
        DOUBLE_ZIGZAG,  // WXY
        COMPLEX_WXY     // Double/triple combination
    }

    /** Type of wave structure this count represents */
    private WaveType waveType;

    /** The timeframe this count was derived from */
    private String primaryTimeframe;

    /** True when the structure points to an upward move, false for a downward move. */
    private boolean bullish;

    /**
     * Mapping from pivot bar index → wave label.
     * Pivot indices correspond to the enriched pivot list for primaryTimeframe.
     */
    private Map<Integer, WaveLabel> pivotToWave;

    /**
     * Ordered list of pivots in this wave count (same order as pivotToWave keys).
     * Allows reconstruction of the wave sequence without a sorted map.
     */
    private List<EnrichedPivot> pivots;

    // ── Current position ──────────────────────────────────────────────────────

    /** The wave we are currently IN (i.e., between the last labeled pivot and current price) */
    private WaveLabel currentWaveInProgress;

    /**
     * Human-readable description of current position.
     * E.g., "Inside Wave 4 correction of daily impulse, sub-wave C of zigzag"
     */
    private String currentPositionDescription;

    // ── Scoring ───────────────────────────────────────────────────────────────

    /** 0–40: how closely pivot ratios match ideal Fibonacci levels */
    private int fibonacciScore;

    /** 0–30: how well indicator behavior matches expected per wave (EWO peak at W3, divergence at W5, etc.) */
    private int indicatorScore;

    /** 0–20: whether lower-TF internal structure matches expectations (5 swings for impulse, 3 for corrective) */
    private int crossTfScore;

    /** 0–10: whether Wave 2/4 alternate in type (sharp vs flat, time, depth) */
    private int alternationScore;

    /** Bonus points awarded by segment proportionality analysis (+0 to +15). Tracked separately for transparency. */
    private int proportionalityBonus;

    /** Combined score: fibonacciScore + indicatorScore + crossTfScore + alternationScore + proportionalityBonus */
    public int totalScore() {
        return fibonacciScore + indicatorScore + crossTfScore + alternationScore + proportionalityBonus;
    }

    /**
     * Returns a breakdown of the total score by component.
     *
     * @return an ordered map with keys: fib, indicators, crossTf, alternation, proportionality
     */
    public java.util.Map<String, Integer> getScoreBreakdown() {
        java.util.Map<String, Integer> m = new java.util.LinkedHashMap<>();
        m.put("fib", fibonacciScore);
        m.put("indicators", indicatorScore);
        m.put("crossTf", crossTfScore);
        m.put("alternation", alternationScore);
        m.put("proportionality", proportionalityBonus);
        return m;
    }

    // ── Key Fibonacci measurements ────────────────────────────────────────────

    /** Wave 2 retracement of Wave 1 as a fraction (e.g., 0.618) */
    private Double wave2RetracePct;

    /** Wave 3 extension of Wave 1 as a fraction (e.g., 1.618) */
    private Double wave3ExtensionPct;

    /** Wave 4 retracement of Wave 3 as a fraction (e.g., 0.382) */
    private Double wave4RetracePct;

    /** Wave B retracement of Wave A (for corrective patterns) */
    private Double waveBRetracePct;

    // ── Indicator flags ───────────────────────────────────────────────────────

    /** True if EWO peak aligns with Wave 3 pivot */
    private boolean ewoW3Peak;

    /** True if MACD/EWO shows divergence at the final wave (W5 or WC) */
    private boolean finalWaveDivergence;

    /** True if volume expanded during Wave 3 */
    private boolean wave3VolumeExpansion;

    /** True if Bollinger Band walk observed during Wave 3 */
    private boolean wave3BollingerWalk;

    // ── Evidence ──────────────────────────────────────────────────────────────

    private List<WaveEvidence> supportingEvidence;
    private List<WaveEvidence> contradictingEvidence;

    // ── Rule violations (if any — should be empty for valid counts) ───────────
    private List<String> ruleViolations;

    /**
     * True if this count was accepted on structural rules only (no Fibonacci ratio validation).
     * These counts represent geometrically-valid but Fibonacci-loose patterns (e.g., extended W3 > 2.618×W1).
     * Scored lower than normal counts; label as "structural-only candidate" in prompt output.
     */
    private boolean structuralOnly;

    public boolean isValid() {
        return ruleViolations == null || ruleViolations.isEmpty();
    }
}
