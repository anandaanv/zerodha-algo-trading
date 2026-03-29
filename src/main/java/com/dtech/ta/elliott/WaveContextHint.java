package com.dtech.ta.elliott;

import lombok.Builder;
import lombok.Data;

/**
 * Reverse inference: given a detected chart pattern, what does it imply about
 * the current Elliott Wave position?
 *
 * Direction of inference:
 *   PatternDetected → WaveContextHint (what wave position are we at?)
 *
 * This is the complement of ScenarioBuilder.expectedPatternsForWave() which goes
 * the other way: given a wave label, what patterns are expected?
 *
 * DESIGN RULE: Hints only increase probability — they never eliminate a wave count.
 * A pattern that disagrees with a wave count adds contradicting evidence, not a veto.
 */
@Data
@Builder
public class WaveContextHint {

    /** The wave position this pattern most likely indicates */
    private WaveLabel impliedCurrentPosition;

    /** The next wave expected after this pattern completes */
    private WaveLabel impliedNextWave;

    /** Is this a sub-wave hint? e.g., "sub-wave 5 of Wave A" */
    private boolean subwaveHint;

    /** If subwave, which parent wave does this belong to? */
    private WaveLabel parentWave;

    /**
     * Probability 0.0–1.0 that this pattern implies the stated wave context.
     * Used as a multiplier on the confluence score boost — never as a gate.
     */
    private double probability;

    /** Price level that, if breached, confirms this interpretation */
    private Double confirmationLevel;

    /** Human-readable description of the confirmation trigger */
    private String confirmationDescription;

    /** Human-readable narrative for the AI prompt */
    private String narrative;
}
