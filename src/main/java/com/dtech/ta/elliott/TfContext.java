package com.dtech.ta.elliott;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Context exported from one timeframe to constrain interpretation of a lower timeframe.
 *
 * Example: 1w says "W4 correction in progress, valid range 20800–22100, hard floor 19800"
 *          This gets passed to 1d analysis, which boosts corrective interpretations
 *          and applies the hard floor as an elimination rule.
 *
 * Cross-TF constraint propagation:
 *   Top-down:   parent TfContext constrains child TF wave count scoring
 *   Bottom-up:  child TF confirmations boost parent TF wave count scores
 */
@Data
@Builder
public class TfContext {

    private String timeframe;

    /** Current wave position inferred from this TF's best wave count */
    private WaveLabel currentPosition;

    /** Structure type of the current position */
    private WaveCount.WaveType structureType;

    /** Is this TF in bullish macro context? (W1/W3/W5 in progress or W2/W4 correcting uptrend) */
    private boolean bullishContext;

    // ── For W4-in-progress context ────────────────────────────────────────────

    /** Minimum depth of expected W4 correction (38.2% retrace of W3) */
    private Double w4SupportMin;

    /** Maximum depth of expected W4 correction (61.8% retrace of W3) */
    private Double w4SupportMax;

    /**
     * Hard floor: W1 top on parent TF.
     * Any child TF wave count whose low breaks this level is eliminated.
     */
    private Double w4HardFloor;

    // ── For correction-in-progress context ───────────────────────────────────

    /** Where the current correction started (Wave A origin) */
    private Double correctionOrigin;

    /** Where the correction is expected to end (Fib support zone) */
    private Double correctionTarget;

    /** The best wave count driving this context */
    private WaveCount drivingCount;

    /** Narrative summary for use in prompt */
    private String narrative;

    /** Score boosts earned from child TF confirmations (populated bottom-up) */
    @Builder.Default
    private int childConfirmationBonus = 0;

    /** List of child TF observations that confirm this context */
    @Builder.Default
    private List<String> childConfirmations = new java.util.ArrayList<>();
}
